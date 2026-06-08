package com.omnivid.api.mysql;

import java.util.List;

public record MysqlExplainResponse(List<MysqlExplainPlan> plans) {
}
