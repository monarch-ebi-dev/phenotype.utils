package monarch.ebi.phenotype.utils;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

public class Entities {
    public static OWLDataFactory df = OWLManager.getOWLDataFactory();
    public static final OWLObjectProperty haspart = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/BFO_0000051"));
    public static final OWLObjectProperty present_in_taxon = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/RO_0002175"));
    public static final OWLObjectProperty inheres_in = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/RO_0000052"));
    public static final OWLObjectProperty inheres_in_part_of = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/RO_0002314"));
    public static final OWLObjectProperty has_phenotype_affecting = df.getOWLObjectProperty(IRI.create("http://purl.obolibrary.org/obo/UPHENO_0000001"));
    public static final OWLAnnotationProperty has_phenotypic_analogue = df.getOWLAnnotationProperty(IRI.create("http://purl.obolibrary.org/obo/UPHENO_0000002"));
    public static String OBOPURLSTRING = "http://purl.obolibrary.org/obo/";
    public static final OWLAnnotationProperty ap_xref = df.getOWLAnnotationProperty(IRI.create("http://www.geneontology.org/formats/oboInOwl#hasDbXref"));

    public static OWLClass cl(String iri) {
        return df.getOWLClass(IRI.create(iri));
    }
}
