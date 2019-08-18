package monarch.ebi.phenotype.utils;

import org.apache.commons.io.FileUtils;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.io.*;
import java.util.*;

/**
 * Hello world!
 */
public class TaxonRestrictionApp {
    private static String OBOPURLSTRING = "http://purl.obolibrary.org/obo/";
    private final Set<OWLClass> preserve_eq = new HashSet<>();
    private final File ontology_file;
    private final File ontology_file_out;
    private final File preserve_eq_file;
    private final OWLClass taxon;
    private final String taxon_label;
    private final OWLClass phenotype_root;
    private static OWLDataFactory df = OWLManager.getOWLDataFactory();
    private static OWLObjectProperty haspart = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/BFO_0000051"));
    private final OWLObjectProperty present_in_taxon = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/RO_0002175"));



    public TaxonRestrictionApp(File ontology_file, File ontology_file_out,String taxon, String taxon_label, String phenotype_root, File preserve_eq_file) throws IOException, OWLOntologyCreationException, OWLOntologyStorageException {
        this.ontology_file = ontology_file;
        this.ontology_file_out = ontology_file_out;
        this.taxon = cl(taxon);
        this.taxon_label = taxon_label;
        this.phenotype_root = cl(phenotype_root);
        this.preserve_eq_file = preserve_eq_file;
        if(preserve_eq_file.isFile()) {
            FileUtils.readLines(preserve_eq_file,"utf-8").forEach(s->preserve_eq.add(df.getOWLClass(IRI.create(s))));
        }
        run();
    }

    private void run() throws IOException, OWLOntologyCreationException, OWLOntologyStorageException {
        OWLOntologyManager man = OWLManager.createOWLOntologyManager();
        OWLOntology o = man.loadOntology(IRI.create(ontology_file));
        OWLReasoner r = new ElkReasonerFactory().createReasoner(o);
        Set<OWLClass> phenotypeClasses = new HashSet<>(r.getSubClasses(phenotype_root,false).getFlattened());
        Set<OWLAxiom> remove = new HashSet<>();
        Set<OWLAxiom> add = new HashSet<>();
        adding_taxon_restrictions(o, remove, add);

        if(!preserve_eq.isEmpty()) {
            for(OWLEquivalentClassesAxiom eq:o.getAxioms(AxiomType.EQUIVALENT_CLASSES, Imports.INCLUDED)) {
                for(OWLClass n:eq.getNamedClasses()) {
                    if(preserve_eq.contains(n)) {
                        remove.add(eq);
                    }
                }
            }
        }

        for(OWLClass p:phenotypeClasses) {
            add_taxon_label(o, remove, add, p);
        }

        log(remove.size());
        log(add.size());

        Set<OWLAxiom> axioms = new HashSet<>(o.getAxioms());
        axioms.removeAll(remove);
        axioms.addAll(add);

        OWLOntology out = man.createOntology(axioms);

        man.saveOntology(out,new FileOutputStream(ontology_file_out));
    }

    private void add_taxon_label(OWLOntology o, Set<OWLAxiom> remove, Set<OWLAxiom> add, OWLClass p) {
        for (OWLAnnotationAssertionAxiom ax : o.getAnnotationAssertionAxioms(p.getIRI())) {
            if(ax.getProperty().isLabel()) {
                remove.add(ax);
                String label = ax.annotationValue().asLiteral().get().getLiteral();
                label = label +" ("+taxon_label+")";
                add.add(df.getOWLAnnotationAssertionAxiom(ax.getProperty(),ax.getSubject(),df.getOWLLiteral(label)));
            }
        }
    }

    private void adding_taxon_restrictions(OWLOntology o, Set<OWLAxiom> remove, Set<OWLAxiom> add) {
        for(OWLAxiom ax:o.getAxioms(AxiomType.EQUIVALENT_CLASSES)) {
            OWLEquivalentClassesAxiom eq = (OWLEquivalentClassesAxiom)ax;
            Set<OWLClass> named_cls = eq.getNamedClasses();
            OWLClass named = null;
            for(OWLClass n:named_cls) {
                named = n;
            }
            if (named_cls.size() == 1) {
                for (OWLClassExpression cein : eq.getClassExpressions()) {
                    if (cein instanceof OWLObjectSomeValuesFrom) {
                        OWLObjectSomeValuesFrom ce = (OWLObjectSomeValuesFrom) cein;
                        if (!ce.getProperty().isAnonymous()) {
                            if (ce.getProperty().asOWLObjectProperty().equals(haspart)) {
                                if (ce.getFiller() instanceof OWLObjectIntersectionOf) {
                                    OWLObjectIntersectionOf ois = (OWLObjectIntersectionOf) ce.getFiller();
                                    Set<OWLClassExpression> operands = new HashSet<>(ois.getOperands());
                                    operands.add(df.getOWLObjectSomeValuesFrom(present_in_taxon,taxon));
                                    OWLObjectIntersectionOf oisn = df.getOWLObjectIntersectionOf(operands);
                                    remove.add(ax);
                                    add.add(df.getOWLEquivalentClassesAxiom(named,df.getOWLObjectSomeValuesFrom(haspart,oisn)));
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void log(Object o) {
        System.out.println(o.toString());
    }


    private OWLClass cl(String iri) {
        return df.getOWLClass(IRI.create(iri));
    }

    public static void main(String[] args) throws OWLOntologyCreationException, IOException, OWLOntologyStorageException {

		String ontology_path = args[0];
        String ontology_path_out = args[1];
        String taxon = args[2];
        String taxon_label = args[3];
        String root_phenotype = args[4];
        String preserve_eq_file_path = args[5];

        /*
        String ontology_path = "/data/hp.owl";
        String ontology_path_out = "/data/hp-taxon.owl";
        String taxon = "http://purl.obolibrary.org/obo/NCBITaxon_9606";
        String taxon_label = "human";
        String root_phenotype = "http://purl.obolibrary.org/obo/HP_0000118";
        */
        File ontology_file = new File(ontology_path);
        File ontology_file_out = new File(ontology_path_out);
        File preserve_eq_file = new File(preserve_eq_file_path);

        new TaxonRestrictionApp(ontology_file, ontology_file_out, taxon, taxon_label, root_phenotype, preserve_eq_file);
    }

}
