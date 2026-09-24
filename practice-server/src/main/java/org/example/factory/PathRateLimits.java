package org.example.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 路径级 curl --limit-rate 规则："源节点->目标节点=速率"，逗号分隔，* 匹配任意节点，
 * 按书写顺序取第一条匹配的规则，例如 "*->master-215=2560k,*->master-40=1536k"。
 */
final class PathRateLimits {
    private static final Pattern RATE = Pattern.compile("\\d+(\\.\\d+)?[kKmMgG]?");
    private final List<String[]> rules;

    private PathRateLimits(List<String[]> rules) {
        this.rules = rules;
    }

    /** 空配置表示没有路径级限速；写错的规则直接报错，避免限速被静默忽略。 */
    static PathRateLimits parse(String spec) {
        List<String[]> rules = new ArrayList<>();
        if (spec != null) {
            for (String item : spec.split(",")) {
                String rule = item.trim();
                if (rule.isEmpty()) continue;
                int arrow = rule.indexOf("->");
                int equals = rule.lastIndexOf('=');
                if (arrow <= 0 || equals <= arrow + 2 || equals == rule.length() - 1) {
                    throw new IllegalArgumentException("invalid curl limit-rate rule: " + rule);
                }
                String source = rule.substring(0, arrow).trim();
                String target = rule.substring(arrow + 2, equals).trim();
                String rate = rule.substring(equals + 1).trim();
                if (source.isEmpty() || target.isEmpty() || !RATE.matcher(rate).matches()) {
                    throw new IllegalArgumentException("invalid curl limit-rate rule: " + rule);
                }
                rules.add(new String[]{source, target, rate});
            }
        }
        return new PathRateLimits(Collections.unmodifiableList(rules));
    }

    /** 第一条匹配规则的速率；没有匹配时为 null。 */
    String resolve(String source, String target) {
        for (String[] rule : rules) {
            if (matches(rule[0], source) && matches(rule[1], target)) return rule[2];
        }
        return null;
    }

    private static boolean matches(String pattern, String node) {
        return "*".equals(pattern) || pattern.equalsIgnoreCase(node);
    }

    @Override
    public String toString() {
        if (rules.isEmpty()) return "(无)";
        List<String> text = new ArrayList<>();
        for (String[] rule : rules) text.add(rule[0] + "->" + rule[1] + "=" + rule[2]);
        return String.join(",", text);
    }
}
