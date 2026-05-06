package io.aeyer.voidcore.instance;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class InstanceFeatureRepository {

    private static final Table<?> INSTANCE_FEATURES = DSL.table("instance_features");
    private static final Field<String> FEATURE_SLUG = DSL.field("feature_slug", String.class);
    private static final Field<Boolean> ENABLED = DSL.field("enabled", Boolean.class);

    private final DSLContext dsl;

    public InstanceFeatureRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<InstanceFeatureState> list() {
        return dsl.select(FEATURE_SLUG, ENABLED)
                .from(INSTANCE_FEATURES)
                .fetchStream()
                .map(r -> new InstanceFeatureState(
                        InstanceFeature.fromSlug(r.get(FEATURE_SLUG)),
                        Boolean.TRUE.equals(r.get(ENABLED))))
                .sorted(Comparator.comparingInt(state -> state.feature().ordinal()))
                .toList();
    }

    public boolean isEnabled(InstanceFeature feature) {
        Boolean enabled = dsl.select(ENABLED)
                .from(INSTANCE_FEATURES)
                .where(FEATURE_SLUG.eq(feature.slug()))
                .fetchOne(ENABLED);
        return enabled == null || enabled;
    }

    public void setEnabled(InstanceFeature feature, boolean enabled) {
        int updated = dsl.update(INSTANCE_FEATURES)
                .set(ENABLED, enabled)
                .where(FEATURE_SLUG.eq(feature.slug()))
                .execute();
        if (updated == 0) {
            dsl.insertInto(INSTANCE_FEATURES)
                    .columns(FEATURE_SLUG, ENABLED)
                    .values(feature.slug(), enabled)
                    .execute();
        }
    }

    public void seedMissingDefaults(Set<InstanceFeature> disabledDefaults) {
        for (InstanceFeature feature : Arrays.asList(InstanceFeature.values())) {
            dsl.insertInto(INSTANCE_FEATURES)
                    .columns(FEATURE_SLUG, ENABLED)
                    .values(feature.slug(), !disabledDefaults.contains(feature))
                    .onConflict(FEATURE_SLUG)
                    .doNothing()
                    .execute();
        }
    }
}
