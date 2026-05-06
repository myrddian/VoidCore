package io.aeyer.voidcore.instance;

import java.util.List;

public class InstanceFeatureService {

    public static final String TOPIC = "instance_features";

    private final InstanceFeatureRepository repo;

    public InstanceFeatureService(InstanceFeatureRepository repo,
                                  InstanceFeatureProperties props) {
        this.repo = repo;
        this.repo.seedMissingDefaults(props.disabledFeatures());
    }

    public List<InstanceFeatureState> list() {
        return repo.list();
    }

    public boolean enabled(InstanceFeature feature) {
        return repo.isEnabled(feature);
    }

    public void setEnabled(InstanceFeature feature, boolean enabled) {
        repo.setEnabled(feature, enabled);
    }
}
