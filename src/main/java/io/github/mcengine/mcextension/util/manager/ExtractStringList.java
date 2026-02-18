package io.github.mcengine.mcextension.util.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Utility for safely extracting string lists from nested YAML maps.
 */
public final class ExtractStringList {
    private ExtractStringList() {}

    /**
     * Gets a list under <code>root[section][key]</code> as strings.
     *
     * @param root    root map
     * @param section section key containing the list
     * @param key     list key
     * @return list of stringified values or empty list when absent
     */
    @SuppressWarnings("unchecked")
    public static List<String> invoke(Map<String, Object> root, String section, String key) {
        if (root == null || !(root.get(section) instanceof Map<?, ?> sectionMap)) {
            return Collections.emptyList();
        }
        Object raw = ((Map<String, Object>) sectionMap).get(key);
        if (raw instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    values.add(String.valueOf(o));
                }
            }
            return values;
        }
        return Collections.emptyList();
    }
}
