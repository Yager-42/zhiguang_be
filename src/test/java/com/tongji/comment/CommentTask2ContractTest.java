package com.tongji.comment;

import com.tongji.counter.schema.CounterSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CommentTask2ContractTest {

    @Test
    void task2ArtifactsExposeRequiredContracts() throws Exception {
        assertFields("com.tongji.comment.api.dto.CommentSubmitResponse",
                "clientRequestId", "pendingCommentId", "status");
        assertFields("com.tongji.comment.event.CommentWriteEvent",
                "commentId", "postId", "rootId", "parentId", "creatorId", "clientRequestId", "body");
        assertFields("com.tongji.comment.event.CommentFeedbackEvent",
                "commentId", "postId", "creatorId", "action");
        assertMethods("com.tongji.comment.mapper.CommentMapper",
                "insert", "findById", "listTopLevelByPost", "listRepliesByRoot", "softDelete", "listCommentIdsCursor");
        assertMethods("com.tongji.comment.mapper.PendingCommentMapper",
                "insert", "findById", "findByCreatorAndClientRequestId", "updateStatus",
                "updateStatusByCreatorAndClientRequestId", "updateStatusIfCurrent");

        String commentXml = Files.readString(Path.of("src/main/resources/mapper/CommentMapper.xml"));
        String pendingXml = Files.readString(Path.of("src/main/resources/mapper/PendingCommentMapper.xml"));

        assertThat(commentXml).contains("id=\"insert\"", "id=\"listTopLevelByPost\"", "id=\"listRepliesByRoot\"",
                "id=\"softDelete\"", "id=\"findById\"", "id=\"listCommentIdsCursor\"",
                "ORDER BY create_time DESC, comment_id DESC");
        assertThat(pendingXml).contains("id=\"insert\"", "id=\"updateStatus\"", "id=\"findById\"",
                "id=\"findByCreatorAndClientRequestId\"", "id=\"updateStatusByCreatorAndClientRequestId\"",
                "id=\"updateStatusIfCurrent\"");
        assertThat(commentXml + pendingXml).doesNotContain("content" + "_key", "content" + "Key");
    }

    @Test
    void pageQueriesReturnDeletedRowsForPlaceholderRendering() throws Exception {
        String commentXml = Files.readString(Path.of("src/main/resources/mapper/CommentMapper.xml"));

        assertThat(normalizeXml(extractStatement(commentXml, "select", "listTopLevelByPost")))
                .doesNotContainPattern("(?i)\\band\\s+(?:\\w+\\.)?status\\s*=\\s*0\\b");
        assertThat(normalizeXml(extractStatement(commentXml, "select", "listRepliesByRoot")))
                .doesNotContainPattern("(?i)\\band\\s+(?:\\w+\\.)?status\\s*=\\s*0\\b");
    }

    @Test
    void pendingStatusCanBeUpdatedOnlyFromExpectedCurrentStatus() throws Exception {
        String pendingXml = Files.readString(Path.of("src/main/resources/mapper/PendingCommentMapper.xml"));
        String updateStatusIfCurrent = normalizeXml(extractStatement(pendingXml, "update", "updateStatusIfCurrent"));

        assertThat(updateStatusIfCurrent).contains("WHERE pending_comment_id = #{pendingCommentId}");
        assertThat(updateStatusIfCurrent).contains("AND status = #{currentStatus}");
        assertThat(updateStatusIfCurrent.indexOf("WHERE "))
                .isLessThan(updateStatusIfCurrent.indexOf("AND status = #{currentStatus}"));
    }

    @Test
    void counterSchemaExposesCommentMetricAtIdx3() {
        assertThat(CounterSchema.NAME_TO_IDX).containsEntry("comment", 3);
        assertThat(CounterSchema.SUPPORTED_METRICS).contains("comment");
    }

    @Test
    void schemaPaginationIndexesMatchCommentCursorQueries() throws Exception {
        String schema = Files.readString(Path.of("db/schema.sql"));

        assertThat(schema).contains("KEY idx_post_comments (post_id, parent_id, create_time, comment_id)");
        assertThat(schema).contains("KEY idx_root_replies (root_id, create_time, comment_id)");
        assertThat(schema).doesNotContain("KEY idx_post_comments (post_id, status, create_time)");
        assertThat(schema).doesNotContain("KEY idx_root_replies (root_id, status, create_time)");
    }

    private static void assertFields(String className, String... fields) throws Exception {
        Class<?> type = Class.forName(className);
        for (String field : fields) {
            assertThat(type.getDeclaredField(field)).isNotNull();
        }
    }

    private static void assertMethods(String className, String... methods) throws Exception {
        Set<String> actual = Set.of(Class.forName(className).getDeclaredMethods()).stream()
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(actual).contains(methods);
    }

    private static String extractStatement(String xml, String element, String id) {
        Pattern pattern = Pattern.compile(
                "<" + element + "\\b[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>.*?</" + element + ">",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xml);
        assertThat(matcher.find()).as(element + " statement " + id).isTrue();
        return matcher.group();
    }

    private static String normalizeXml(String xml) {
        return xml.replaceAll("\\s+", " ").trim();
    }
}
