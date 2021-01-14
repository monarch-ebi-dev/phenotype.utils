package monarch.ebi.phenotype.utils;

import org.semanticweb.owlapi.model.IRI;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class LinkBetweenEntity {
    final IRI e1;
    final IRI e2;
    final IRI relation;
    final Map<String,String> additionalMetadata = new HashMap<>();

    public LinkBetweenEntity(IRI e1, IRI e2, IRI relation) {
        this.e1 = e1;
        this.e2 = e2;
        this.relation = relation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkBetweenEntity that = (LinkBetweenEntity) o;
        return Objects.equals(e1, that.e1) &&
                Objects.equals(e2, that.e2) &&
                Objects.equals(relation, that.relation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(e1, e2, relation);
    }

    public Map<String,String> getLinkData() {
        Map<String,String> map = new HashMap<>();
        map.put("e1", e1.toString());
        map.put("e2", e2.toString());
        map.put("r", relation.toString());
        return map;
    }

    @Override
    public String toString() {
        return "("+e1+")-["+relation+"]->("+e2+")";
    }

}
