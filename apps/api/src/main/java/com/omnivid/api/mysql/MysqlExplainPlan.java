package com.omnivid.api.mysql;

public record MysqlExplainPlan(
        String scenario,
        String hook,
        String tableName,
        String accessType,
        String possibleKeys,
        String keyName,
        long rows,
        String filtered,
        String extra
) {
}
