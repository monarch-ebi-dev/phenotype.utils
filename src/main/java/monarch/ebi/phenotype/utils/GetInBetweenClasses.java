package monarch.ebi.phenotype.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.yaml.snakeyaml.Yaml;

/**
 * Hello world!
 */
public class GetInBetweenClasses {
    private static String OBOPURLSTRING = "http://purl.obolibrary.org/obo/";
    private final List<String> legal_filler_patterns = new ArrayList<>();
    private final Set<String> legal_patterns_vars_set = new HashSet<>();
    private final File ontology_file;
    private final File oid_pattern_matches_dir;
    private final File pattern_dir;
    private final File oid_upheno_fillers_dir;
    private final File legal_filler_iri_patterns;
    private final File legal_pattern_vars;
    private static OWLDataFactory df = OWLManager.getOWLDataFactory();

    public GetInBetweenClasses(File ontology_file, File oid_pattern_matches_dir, File pattern_dir, File oid_upheno_fillers_dir, File legal_filler_iri_patterns, File legal_pattern_vars) throws IOException, OWLOntologyCreationException {
        this.ontology_file = ontology_file;
        this.oid_pattern_matches_dir = oid_pattern_matches_dir;
        this.pattern_dir = pattern_dir;
        this.oid_upheno_fillers_dir = oid_upheno_fillers_dir;
        this.legal_filler_iri_patterns = legal_filler_iri_patterns;
        this.legal_pattern_vars = legal_pattern_vars;
        run();
    }

    private void run() throws IOException, OWLOntologyCreationException {
        legal_filler_patterns.addAll(FileUtils.readLines(this.legal_filler_iri_patterns,"utf-8"));
        legal_patterns_vars_set.addAll(FileUtils.readLines(this.legal_pattern_vars,"utf-8"));
        OWLOntology o = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(ontology_file));
        OWLReasoner r = new ElkReasonerFactory().createReasoner(o);
        for (File tsv_file : oid_pattern_matches_dir.listFiles((dir, name) -> name.toLowerCase().endsWith(".tsv"))) {
            extract_fillers_for_tsv(r, tsv_file);
        }

    }

    private void extract_fillers_for_tsv(OWLReasoner r, File tsv_file) throws IOException {
        File pattern = new File(pattern_dir, tsv_file.getName().replaceAll(".tsv$", ".yaml"));
        List<Map<String, String>> tsv = loadTsv(tsv_file);
        if(!tsv.isEmpty()) {
            log("Processing TSV: "+tsv_file+" , size: "+tsv.size());
            Map<String, Object> obj = loadPattern(pattern);
            Map<String, OWLClass> filler = getFillerClassesFromPattern(obj);
            //log("Filler: "+filler.toString());
            List<String> filler_columns = new ArrayList<>(filler.keySet());
            //log("Filler columns: "+filler_columns.toString());
            List<List<List<OWLClass>>> all_filler_combinations = computeAllFillerCombinations(r, tsv, filler, filler_columns);
            log("Computing final set..");

            Set<Map<String, String>> upheno_fillers = transformFillerCombinationsToTsvRecords(filler_columns, all_filler_combinations);
            //log(upheno_fillers);
            log("Cartesian product size: "+upheno_fillers.size());
            exportTSVs(upheno_fillers,filler_columns,tsv_file.getName());
            //System.exit(0);
        }
    }

    private Set<Map<String, String>> transformFillerCombinationsToTsvRecords(List<String> filler_columns, List<List<List<OWLClass>>> all_filler_combinations) {
        Set<Map<String, String>> upheno_fillers = new HashSet<>();
        for (List<List<OWLClass>> filler_map : all_filler_combinations) {
            List<List<OWLClass>> cp = computeCartesianProduct(filler_map);
            for(List<OWLClass> row:cp) {
                Map<String,String> rec = new HashMap<>();
                for (int i = 0; i < filler_columns.size(); i++) {
                    try {
                        rec.put(filler_columns.get(i), row.get(i).getIRI().toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                        log(row);
                        log(filler_columns);
                        System.exit(0);
                    }
                }
                upheno_fillers.add(rec);
            }
        }
        return upheno_fillers.stream().distinct().collect(Collectors.toSet());
    }

    private List<List<OWLClass>> computeCartesianProduct(List<List<OWLClass>> fillers) {
        List<List<OWLClass>> cp = new ArrayList<>();
        if(fillers.size()==1) {
            //log("Only one feature, no cartesian product necessary.");
            cp.addAll(fillers.stream().distinct().collect(Collectors.toList()));
        }
        else {
            //log("More than one feature, computing cartesian product.");
            //log(filler_map);
            //filler_map.forEach(l->log(l.size()));
            cp.addAll(Lists.cartesianProduct(fillers).stream().distinct().collect(Collectors.toList()));
        }
        return cp;
    }

    private List<List<List<OWLClass>>> computeAllFillerCombinations(OWLReasoner r, List<Map<String, String>> tsv, Map<String, OWLClass> filler, List<String> filler_columns) {
        List<List<List<OWLClass>>> all_filler_combinations = new ArrayList<>();
        log("Computing all filler combinations..");
        for (Map<String, String> rec : tsv) {
            // The filler_combinations will contain one list of owlclasses for each feature (anatomical_entity, biological_process, etc)
            List<List<OWLClass>> filler_combinations = new ArrayList<>();
            boolean at_least_one_column_no_fillers = false;
            for (String filler_col : filler_columns) {
                OWLClass cl = cl(rec.get(filler_col));
                Set<OWLClass> entities_inf = new HashSet<>();
                // Computing all inferences between the filler class declared in the pattern and the class in the record. Filtering out those fillers that are not declared legal, species independent fillers in the legal_filler.txt file. If the current column is configured to be expanded to superclasses, it is done, else only the entity itself is taken over.
                entities_inf.addAll(between(cl, filler.get(filler_col), r, legal_patterns_vars_set.contains(filler_col)).stream().filter(this::legalFiller).collect(Collectors.toList()));
                if(entities_inf.isEmpty()) {
                    log("No fillers found for class: "+cl);
                    at_least_one_column_no_fillers = true;
                    break;
                } else {
                    filler_combinations.add(new ArrayList<>(entities_inf));
                }

            }
            if(at_least_one_column_no_fillers) {
                log("At least one column has no fillers for rec: "+rec);
            } else {
                all_filler_combinations.add(filler_combinations);
            }
        }
        return all_filler_combinations;
    }

    private boolean legalFiller(OWLClass owlClass) {
        for(String iripattern:this.legal_filler_patterns) {
            if(owlClass.getIRI().toString().startsWith(iripattern)) {
                return true;
            }
        }
        return false;
    }

    private Map<String,OWLClass> getFillerClassesFromPattern(Map<String, Object> obj) {
        Map<String,OWLClass> fillers = new HashMap<>();
        Map<String,Object> classes = (Map<String, Object>) obj.get("classes");
        Map<String,Object> vars = (Map<String, Object>) obj.get("vars");
        for(String var:vars.keySet()) {
            //log(var);
            //log(vars.get(var));
            String cl = classes.get(vars.get(var).toString().replaceAll("'","")).toString().trim();
            if(cl.equals("owl:Thing")) {
                fillers.put(var, df.getOWLThing());
            } else {
                String iri = OBOPURLSTRING + cl.replaceAll(":", "_");
                fillers.put(var, cl(iri));
            }
        }
        return fillers;
    }

    private void log(Object o) {
        System.out.println(o.toString());
    }

    private Collection<? extends OWLClass> between(OWLClass e, OWLClass filler, OWLReasoner r, boolean superCls) {
        //log(e);
        //log(filler);
        List<OWLClass> between = new ArrayList<>();
        Set<OWLClass> superClasses = new HashSet<>();
        if(superCls) {
            superClasses.addAll(r.getSuperClasses(e, false).getFlattened());
            superClasses.add(e);
        } else {
            superClasses.add(e);
        }
        if(!r.getSuperClasses(e, false).getFlattened().contains(filler)) {
            log(e +" is not a legal instance of the filler! This should not happen.");
            return between;
        }
        between.addAll(superClasses);
        between.removeAll(r.getSuperClasses(filler, false).getFlattened());
        return between;
    }

    public OWLClass cl(String iri) {
        return df.getOWLClass(IRI.create(iri));
    }

    public static void main(String[] args) throws OWLOntologyCreationException, IOException {

		String ontology_path = args[0];
		String oid_pattern_matches_dir_path = args[1];
		String pattern_dir_path = args[2];
        String oid_upheno_fillers_dir_path = args[3];
        String legal_filler_iri_patterns_path = args[4];
        String legal_pattern_vars_path = args[5];

       /* String ontology_path = "/ws/upheno-dev/src/curation/ontologies-for-matching/mp.owl";
        String oid_pattern_matches_dir_path = "/ws/upheno-dev/src/curation/pattern-matches/mp";
        String pattern_dir_path = "/ws/upheno-dev/src/curation/patterns-for-matching/";
        String oid_upheno_fillers_dir_path = "/ws/upheno-dev/src/curation/upheno-fillers/mp";
        String legal_filler_iri_patterns_path = "/ws/upheno-dev/src/curation/legal_fillers.txt";
        String legal_pattern_vars_path = "/ws/upheno-dev/src/curation/legal_pattern_vars.txt";
*/
        File ontology_file = new File(ontology_path);
        File oid_pattern_matches_dir = new File(oid_pattern_matches_dir_path);
        File pattern_dir = new File(pattern_dir_path);
        File oid_upheno_fillers_dir = new File(oid_upheno_fillers_dir_path);
        File legal_filler_iri_patterns = new File(legal_filler_iri_patterns_path);
        File legal_pattern_vars = new File(legal_pattern_vars_path);

        new GetInBetweenClasses(ontology_file, oid_pattern_matches_dir, pattern_dir, oid_upheno_fillers_dir, legal_filler_iri_patterns, legal_pattern_vars);
    }

    private Map<String, Object> loadPattern(File pattern) throws FileNotFoundException {
        Yaml yaml = new Yaml();
        InputStream inputStream = new FileInputStream(pattern);
        Map<String, Object> obj = yaml.load(inputStream);
        return obj;
    }

    private List<Map<String, String>> loadTsv(File tsv_file) throws IOException {
        List<Map<String, String>> tsv = new ArrayList<>();
        List<String> lines = FileUtils.readLines(tsv_file, "utf-8");
        boolean first = true;
        String[] header = null;
        for (String l : lines) {
            Map<String, String> rec = new HashMap<>();
            String[] row = l.split("\\t");
            if (first) {
                header = row;
                first = false;
            } else {
                for (int i = 0; i < header.length; i++) {
                    rec.put(header[i], row[i]);
                }
                tsv.add(rec);
            }

        }
        return tsv;
    }


    private void exportTSVs(Collection<Map<String,String>> fillers, List<String> columns, String tsvname) {
        List<String> outlines = new ArrayList<>();
        File tsvf = new File(oid_upheno_fillers_dir,tsvname);
        outlines.add(String.join("\t", columns));
        for (Map<String,String> rec : fillers) {
            String s = "";
            for(String col:columns) {
                s+= rec.get(col)+"\t";
            }
            outlines.add(s.trim());
        }

        try {
            System.out.println("Exporting to file: "+tsvf);
            FileUtils.writeLines(tsvf, outlines);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

	/*
	private void exportTSVs(List<FillerData> fillers) {
		System.out.println("EXPORTING...");
		for (FillerData pattern : fillers) {
			System.out.println(pattern);
			File tsvf = new File(outdir,pattern.getPattern().trim().replaceAll(".yaml\\:$", ".tsv").trim());
			
			Map<Filler, List<OWLClass>> fd = pattern.getFillers();
			List<String> columns = new ArrayList<>();
			List<List<OWLClass>> all = new ArrayList<>();
			for (Filler col : fd.keySet()) {
				columns.add(col.getColumn());
				all.add(fd.get(col));
			}
			List<List<OWLClass>> rows = multiply(all);
			//rows.forEach(list->{System.out.println("BLOCK"); list.forEach(System.out::println);});
			List<String> outlines = new ArrayList<>();
			String headrow = "";
			for(String col:columns) {
				headrow += col+"\t";
			}
			outlines.add(headrow.trim());
			
			for(List<OWLClass> row:rows) {
				String line = "";
				for(OWLClass c:row) {
					line+=c.getIRI().toString()+"\t";
				}
				outlines.add(line.trim());
			}
			try {
				System.out.println("Exporting to file: "+tsvf);
				FileUtils.writeLines(tsvf, outlines);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	/*private String generateID(String pattern,List<OWLClass> row) {
		StringBuilder sb = new StringBuilder();
		String s=pattern;
		row.forEach(c->sb.append(c.getIRI().toString()));
		for(OWLClass c:row) {
			s+=c.getIRI().toString();
		}
		System.out.println(s);
		System.out.println(s.hashCode());
		System.out.println(s.toString().hashCode());
		return "http://purl.obolibrary.org/obo/upheno/UPHENO_"+;
	}*/
	/*
	//https://codereview.stackexchange.com/questions/129065/form-every-unique-combination-of-the-items-of-a-list-of-lists
	private static void multiply(List<List<OWLClass>> factors, List<OWLClass> current, List<List<OWLClass>> results) {
        if (current.size() >= factors.size()) {
            // Don't really need to make a deeper copy with String values
            // but might as well in case we change types later.
            List<OWLClass> result = new ArrayList<>();
            for (OWLClass s : current) {
                result.add(s);
            }
            results.add(result);

            return;
        }

        int currentIndex = current.size();
        for (OWLClass s : factors.get(currentIndex)) {
            current.add(s);
            multiply(factors, current, results);
            current.remove(currentIndex);
        }
    }

    private static List<List<OWLClass>> multiply(List<List<OWLClass>> factors) {
        List<List<OWLClass>> results = new ArrayList<>();
        multiply(factors, new ArrayList<>(), results);
        return results;
    }

	private void extractFillerHierarchies(List<FillerData> fillers) {
		System.out.println("extractHierarchies..");
		for (FillerData pattern : fillers) {
			System.out.println(pattern);
			Map<Filler, List<OWLClass>> fd = pattern.getFillers();
			for (Filler upper : fd.keySet()) {
				//System.out.println(upper);
				Set<OWLClass> subclasses = getSubClasses(upper.getOWLClass());
				List<OWLClass> fillerclasses = fd.get(upper);
				Set<OWLClass> superclasses = getSuperClasses(fillerclasses);
				Set<OWLClass> inter = new HashSet<>();
				
				if(superclasses.contains(upper.getOWLClass())) {
					inter.addAll(subclasses);
					inter.retainAll(superclasses);
				} else {
					inter.addAll(superclasses);
				}
				inter.addAll(fillerclasses);
				inter.add(upper.getOWLClass());
				System.out.println("filler: "+fillerclasses.size());
				//fillerclasses.forEach(System.out::println);
				System.out.println("subclasses: "+subclasses.size());
				//subclasses.forEach(System.out::println);
				System.out.println("superclasses: "+superclasses.size());
				superclasses.forEach(System.out::println);
				System.out.println("inter: "+inter.size());
				//inter.forEach(System.out::println);
				pattern.addFiller(upper, new ArrayList<>(inter));
			}

		}
	}

	private void prepareReasoners(List<String> ontology_iris) throws OWLOntologyCreationException {
		System.out.println("Loading UBERON");
		OWLOntology o_uberon = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(UBERON));
		SyntacticLocalityModuleExtractor slme = new SyntacticLocalityModuleExtractor(o_uberon.getOWLOntologyManager(),o_uberon, ModuleType.BOT);


		for (String iri_s : ontology_iris) {
			System.out.println("Loading ontology: |" + iri_s+"|");
			OWLOntology o = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(iri_s));

			Set<OWLAxiom> uberonalign = new HashSet<>();

			for(OWLClass c:o.getClassesInSignature(Imports.INCLUDED)) {
				Set<OWLClass> uberonxrefs = getUberonXREFS(c,o);
				for(OWLClass ux:uberonxrefs) {
					uberonalign.add(daf.getOWLSubClassOfAxiom(c,ux));
				}
			}
			if(!uberonalign.isEmpty()) {
				System.out.println("Adding UBERON in..");
				o.getOWLOntologyManager().addAxioms(o, uberonalign);
				Set<OWLEntity> sig = new HashSet<>(o.getClassesInSignature(Imports.INCLUDED));
				Set<OWLAxiom> umodule = slme.extract(sig);
				o.getOWLOntologyManager().addAxioms(o, umodule);
				OWLOntologyManager m = OWLManager.createOWLOntologyManager();
				String on = iri_s.replaceAll(OBOPURLSTRING,"").replaceAll("[.]owl$","").replaceAll("[^a-zA-Z0-9_]","");
				String filename = on+"-uberon-mappings.owl";
				OWLOntology o_mapping = m.createOntology(uberonalign,IRI.create(UPHENOMAPPINGBASE+filename));
				try {
					m.saveOntology(o_mapping,new FileOutputStream(new File(outdir,filename)));
				} catch (OWLOntologyStorageException e) {
					e.printStackTrace();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
			OWLReasoner r = new ElkReasonerFactory().createReasoner(o);
			reasoners.add(r);
		}
	}

	private Set<OWLClass> getUberonXREFS(OWLClass c, OWLOntology o) {
		Set<OWLClass> xrefs = new HashSet<>();
		for(OWLAnnotation annotation: EntitySearcher.getAnnotations(c,o,ap_xref)) {

			OWLAnnotationValue value = annotation.getValue();
			String iri = value.asLiteral().or(daf.getOWLLiteral("")).getLiteral();
				if(iri.toString().startsWith("http://purl.obolibrary.org/obo/UBERON_")) {
					xrefs.add(daf.getOWLClass(IRI.create(iri)));
				} else if(iri.toString().startsWith("UBERON:")) {
					xrefs.add(daf.getOWLClass(IRI.create(iri.replaceAll("UBERON:","http://purl.obolibrary.org/obo/UBERON_"))));
				}
		}
		return xrefs;
	}

	private Set<OWLClass> getSuperClasses(Collection<OWLClass> fillerclasses) {
		Set<OWLClass> fillers = new HashSet<>();
		for (OWLReasoner r : reasoners) {
			for (OWLClass c : fillerclasses) {
				Set<OWLClass> add = r.getSuperClasses(c, false).getFlattened();
				add.removeAll(r.getUnsatisfiableClasses().getEntities());
				fillers.addAll(add);
			}
		}
		fillers.remove(daf.getOWLThing());
		fillers.remove(daf.getOWLNothing());
		return fillers;
	}

	private Set<OWLClass> getSubClasses(OWLClass upper) {
		Set<OWLClass> fillers = new HashSet<>();
		for (OWLReasoner r : reasoners) {
			Set<OWLClass> add = r.getSubClasses(upper, false).getFlattened();
			add.removeAll(r.getUnsatisfiableClasses().getEntities());
			fillers.addAll(add);
		}
		fillers.remove(daf.getOWLThing());
		fillers.remove(daf.getOWLNothing());
		return fillers;
	}

	private static OWLClass oc(String s) {
		return daf.getOWLClass(IRI.create(s));
	}

	public static void main(String[] args) throws OWLOntologyCreationException, IOException {
		String ontology_path = args[0];
		String oid_pattern_matches_dir_path = args[1];
		String pattern_dir_path = args[2];
		String oid_upheno_fillers_dir_path = args[3];

		File ontology_file = new File(ontology_path);
		File oid_pattern_matches_dir = new File(oid_pattern_matches_dir_path);
		File pattern_dir = new File(pattern_dir_path);
		File oid_upheno_fillers_dir = new File(oid_upheno_fillers_dir_path);

		new GetInBetweenClasses(ontology_file, oid_pattern_matches_dir, pattern_dir, oid_upheno_fillers_dir);
	}

	private static List<FillerData> prepare_fillers(File fillersf) throws IOException {
		List<String> yaml = FileUtils.readLines(fillersf, Charset.forName("utf-8"));
		// yaml.stream().forEach(System.out::println);
		OWLClass filler = null;
		String col = "";
		Set<OWLClass> fillers = new HashSet<>();
		List<FillerData> df = new ArrayList<>();
		FillerData fd = null;
		for (String s : yaml) {
			if (s.matches("^[a-zA-Z].*")) {
				if (fd != null) {
					if(!fillers.isEmpty()) {
						fd.addFiller(new Filler(filler,col), new ArrayList<>(fillers));
						fillers.clear();
						col = "";
						filler = null;
					}
					if (!fd.isEmpty()) {
						df.add(fd);
					}
				}
				fd = new FillerData(s);
			} else if (s.matches("^\\s*filler.*")) {
				filler = oc(s.replaceAll("filler\\:", "").trim());
			} else if (s.matches("^\\s*keys.*")) {
				// System.out.println(s);
			} else if (s.matches(".*[-]\\s.*")) { // Subclass
				fillers.add(oc(s.replaceAll(" - ", "").trim()));
			} else { //column name..
				if(!fillers.isEmpty()) {
				fd.addFiller(new Filler(filler,col), new ArrayList<>(fillers));
				fillers.clear();
				}
				col = s.replaceAll(":", "").trim(); // col
				filler = null;
				
			} 
		}
		return df;
	}
	*/
}
