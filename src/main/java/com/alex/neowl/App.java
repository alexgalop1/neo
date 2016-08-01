package com.alex.neowl;

import java.io.File;

import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.rest.graphdb.RestGraphDatabase;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

public class App {


    private GraphDatabaseService db;

    public static void main(String[] args) throws Exception {
        App app = new App();
        app.loadOntology();
    }
    public App() {
        getGraphDb();
    }

    private void importOntology(OWLOntology ontology) throws Exception {
        OWLReasoner reasoner = new Reasoner(ontology);

        if (!reasoner.isConsistent()) {
            System.out.println("Ontology is inconsistent");
            throw new Exception("Ontology is inconsistent");
        }
        System.out.println("Ontology is consistent");
        Transaction tx = db.beginTx();
            Node thingNode = getOrCreateNodeWithUniqueFactory("owl:Thing");

        // For each class of the ontology, we create a node and a relation isA to its superclass
            for (OWLClass c :ontology.getClassesInSignature(true)) {
                String classString = c.toString();
                if (classString.contains("#")) {
                    classString = classString.substring(classString.indexOf("#")+1,classString.lastIndexOf(">"));
                }
                Node classNode = getOrCreateNodeWithUniqueFactory(classString);
                for(OWLAnnotationAssertionAxiom annotations :c.getAnnotationAssertionAxioms(ontology)) {
                    String valueStr = annotations.getValue().toString();
                    if(valueStr.contains("@")){
                        String locale = valueStr.substring(valueStr.lastIndexOf("@"), valueStr.length());
                        String valueStrNoLocale = valueStr.substring(0, valueStr.lastIndexOf("@"));
                        classNode.setProperty(annotations.getProperty().getIRI().getFragment()+locale, valueStrNoLocale);
                    } else {
                        classNode.setProperty(annotations.getProperty().getIRI().getFragment(), valueStr);
                    }

                 }

                NodeSet<OWLClass> superclasses = reasoner.getSuperClasses(c, true);

                if (superclasses.isEmpty()) {
                    classNode.createRelationshipTo(thingNode,DynamicRelationshipType.withName("isA"));
                } else {
                    for (org.semanticweb.owlapi.reasoner.Node<OWLClass>parentOWLNode: superclasses) {

                        OWLClassExpression parent = parentOWLNode.getRepresentativeElement();
                        String parentString = parent.toString();
                        if (parentString.contains("#")) {
                            parentString = parentString.substring(parentString.indexOf("#")+1,parentString.lastIndexOf(">"));
                        }
                        Node parentNode = getOrCreateNodeWithUniqueFactory(parentString);
                        classNode.createRelationshipTo(parentNode,DynamicRelationshipType.withName("isA"));
                    }
                }


                // For each individual, we create a node and link it to its parent
                for (org.semanticweb.owlapi.reasoner.Node<OWLNamedIndividual> in : reasoner.getInstances(c, true)) {
                    OWLNamedIndividual i = in.getRepresentativeElement();
                    String indString = i.toString();
                    if (indString.contains("#")) {
                        indString = indString.substring(indString.indexOf("#")+1,indString.lastIndexOf(">"));
                    }
                    Node individualNode = getOrCreateNodeWithUniqueFactory(indString);

                    individualNode.createRelationshipTo(classNode,DynamicRelationshipType.withName("isA"));

                    //For each property used on the instance
                    for (OWLObjectPropertyExpression objectProperty:
                            ontology.getObjectPropertiesInSignature()) {

                        for
                                (org.semanticweb.owlapi.reasoner.Node<OWLNamedIndividual> object: reasoner.getObjectPropertyValues(i,objectProperty)) {
                            String reltype = objectProperty.toString();
                            reltype = reltype.substring(reltype.indexOf("#")+1, reltype.lastIndexOf(">"));

                            String s = object.getRepresentativeElement().toString();
                            s = s.substring(s.indexOf("#")+1,s.lastIndexOf(">"));
                            Node objectNode = getOrCreateNodeWithUniqueFactory(s);
                            individualNode.createRelationshipTo(objectNode, DynamicRelationshipType.withName(reltype));
                        }
                    }

                    for (OWLDataPropertyExpression dataProperty:
                            ontology.getDataPropertiesInSignature()) {

                        for (OWLLiteral object: reasoner.getDataPropertyValues(i, dataProperty.asOWLDataProperty())) {
                            String reltype =dataProperty.asOWLDataProperty().toString();
                            reltype = reltype.substring(reltype.indexOf("#")+1, reltype.lastIndexOf(">"));

                            String s = object.toString();
                            individualNode.setProperty(reltype, s);
                        }
                    }
                }
            }


            // For each object property, we create links between nodes
            for (OWLObjectPropertyExpression objectProperty:ontology.getObjectPropertiesInSignature()) {
                for  (org.semanticweb.owlapi.reasoner.Node<OWLClass> domain: reasoner.getObjectPropertyDomains(objectProperty, true)) {

                    OWLClassExpression domainOwl = domain.getRepresentativeElement();
                    String domainString = domainOwl.toString();
                    if (domainString.contains("#")) {
                        domainString = domainString.substring(domainString.indexOf("#")+1,domainString.lastIndexOf(">"));
                    }
                    Node domainNode = getOrCreateNodeWithUniqueFactory(domainString);

                    String reltype = objectProperty.toString();
                    reltype = reltype.substring(reltype.indexOf("#")+1, reltype.lastIndexOf(">"));

                    for  (org.semanticweb.owlapi.reasoner.Node<OWLClass> range: reasoner.getObjectPropertyRanges(objectProperty, true)) {

                        OWLClassExpression rangeOwl = range.getRepresentativeElement();
                        String rangeString = rangeOwl.toString();
                        if (rangeString.contains("#")) {
                            rangeString = rangeString.substring(rangeString.indexOf("#")+1,rangeString.lastIndexOf(">"));
                        }
                        Node rangeNode = getOrCreateNodeWithUniqueFactory(rangeString);
                        domainNode.createRelationshipTo(rangeNode, DynamicRelationshipType.withName(reltype));

                    }
                }
            }
            tx.success();
    }


    private Node getOrCreateNodeWithUniqueFactory(String s) {
        Node dataNode = null;
        Label label = DynamicLabel.label("Ontology");
            Index<Node> nodeIndex = getGraphDb().index().forNodes("OntClasses");
            dataNode = nodeIndex.get("name",s).getSingle();
            if(dataNode == null) {
                dataNode = db.createNode(label);
                dataNode.setProperty("name", s);
                nodeIndex.add(dataNode, "name", s);
            }
        return dataNode;
    }



    public void loadOntology() throws Exception {
        File fileBase = new File("src/main/resources/pizza.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology pizzaOntology = manager.loadOntologyFromOntologyDocument(fileBase);
        importOntology(pizzaOntology);
        System.out.println("Load ontology");
    }

    public GraphDatabaseService getGraphDb() {

        if (db == null) {
            db= new RestGraphDatabase("http://localhost:7474/db/data");
        }

        return db;
    }
}
