package com.tencent.supersonic.chat.parser;

import com.tencent.supersonic.auth.api.authentication.pojo.User;
import com.tencent.supersonic.chat.api.component.SemanticParser;
import com.tencent.supersonic.chat.api.component.SemanticQuery;
import com.tencent.supersonic.chat.api.pojo.ChatContext;
import com.tencent.supersonic.chat.api.pojo.ModelSchema;
import com.tencent.supersonic.chat.api.pojo.QueryContext;
import com.tencent.supersonic.chat.api.pojo.SchemaElement;
import com.tencent.supersonic.chat.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.chat.api.pojo.SemanticSchema;
import com.tencent.supersonic.chat.api.pojo.response.SqlInfo;
import com.tencent.supersonic.chat.query.llm.s2sql.S2SQLQuery;
import com.tencent.supersonic.chat.query.rule.RuleSemanticQuery;
import com.tencent.supersonic.chat.service.SemanticService;
import com.tencent.supersonic.common.pojo.QueryType;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.jsqlparser.SqlParserSelectHelper;
import com.tencent.supersonic.knowledge.service.SchemaService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Query type parser, determine if the query is a metric query, an entity query,
 * or another type of query.
 */
@Slf4j
public class QueryTypeParser implements SemanticParser {

    @Override
    public void parse(QueryContext queryContext, ChatContext chatContext) {

        List<SemanticQuery> candidateQueries = queryContext.getCandidateQueries();
        User user = queryContext.getRequest().getUser();

        for (SemanticQuery semanticQuery : candidateQueries) {
            // 1.init S2SQL
            semanticQuery.initS2Sql(user);
            // 2.set queryType
            QueryType queryType = getQueryType(semanticQuery);
            semanticQuery.getParseInfo().setQueryType(queryType);
        }
    }

    private QueryType getQueryType(SemanticQuery semanticQuery) {
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        SqlInfo sqlInfo = parseInfo.getSqlInfo();
        if (Objects.isNull(sqlInfo) || StringUtils.isBlank(sqlInfo.getS2SQL())) {
            return QueryType.OTHER;
        }
        //1. entity queryType
        if (semanticQuery instanceof RuleSemanticQuery || semanticQuery instanceof S2SQLQuery) {
            //If all the fields in the SELECT statement are of tag type.
            List<String> selectFields = SqlParserSelectHelper.getSelectFields(sqlInfo.getS2SQL());
            SemanticService semanticService = ContextUtils.getBean(SemanticService.class);
            ModelSchema modelSchema = semanticService.getModelSchema(parseInfo.getModelId());

            if (CollectionUtils.isNotEmpty(selectFields) && Objects.nonNull(modelSchema) && CollectionUtils.isNotEmpty(
                    modelSchema.getTags())) {
                Set<String> tags = modelSchema.getTags().stream().map(schemaElement -> schemaElement.getName())
                        .collect(Collectors.toSet());
                if (tags.containsAll(selectFields)) {
                    return QueryType.TAG;
                }
            }
        }
        //2. metric queryType
        List<String> selectFields = SqlParserSelectHelper.getSelectFields(sqlInfo.getS2SQL());
        SemanticSchema semanticSchema = ContextUtils.getBean(SchemaService.class).getSemanticSchema();
        List<SchemaElement> metrics = semanticSchema.getMetrics(parseInfo.getModelId());
        if (CollectionUtils.isNotEmpty(metrics)) {
            Set<String> metricNameSet = metrics.stream().map(metric -> metric.getName()).collect(Collectors.toSet());
            boolean containMetric = selectFields.stream().anyMatch(selectField -> metricNameSet.contains(selectField));
            if (containMetric) {
                return QueryType.METRIC;
            }
        }
        return QueryType.OTHER;
    }

}