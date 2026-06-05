package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法律文档按"条"切分器。
 * 以法律条文（第X条）为单位切分，每个片段附带法律名称、章节、条号元数据，
 * 并在文本开头注入上下文信息以提升向量检索的语义匹配度。
 */
public class LegalDocumentSplitter implements DocumentSplitter {

    private static final String CN_NUMS = "一二三四五六七八九十百千零○〇0-9";

    /** 匹配法条开头，如 "第一条　"、"第二百零五条　"、"第二十六条之一　" */
    private static final Pattern ARTICLE_PATTERN =
            Pattern.compile("^[　\\s]*第[" + CN_NUMS + "]+条[之的]?[" + CN_NUMS + "]*[　\\s]");

    /** 匹配结构标题，如 "第一编　总则"、"第三章　物权的保护"、"第二节　监护" */
    private static final Pattern STRUCTURE_PATTERN =
            Pattern.compile("^[　\\s]*(第[" + CN_NUMS + "]+(?:编|分编|章|节))[　\\s]+(.+)");

    /** 提取条号，如 "第一百四十八条" */

    private static final Pattern ARTICLE_NUM_PATTERN =
            Pattern.compile("第[" + CN_NUMS + "]+条[之的]?[" + CN_NUMS + "]*");

    @Override
    public List<TextSegment> split(Document document) {
        String lawName = extractLawName(document);
        String[] lines = document.text().split("\\r?\\n");
        List<TextSegment> segments = new ArrayList<>();

        String currentChapter = "";
        StringBuilder currentArticleText = null;
        String currentArticleNum = null;
        String currentArticleChapter = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 检查是否为结构标题（编/章/节），仅更新上下文，不作为独立片段
            Matcher structMatcher = STRUCTURE_PATTERN.matcher(line);
            if (structMatcher.find()) {
                currentChapter = structMatcher.group(1) + " " + structMatcher.group(2);
                continue;
            }

            // 检查是否为新的法条
            Matcher articleMatcher = ARTICLE_PATTERN.matcher(line);
            if (articleMatcher.find()) {
                // 保存上一条
                if (currentArticleText != null) {
                    segments.add(buildSegment(
                            currentArticleText.toString(), lawName,
                            currentArticleChapter, currentArticleNum));
                }
                // 开始新的一条
                Matcher numMatcher = ARTICLE_NUM_PATTERN.matcher(line);
                currentArticleNum = numMatcher.find() ? numMatcher.group(0) : "";
                currentArticleChapter = currentChapter;
                currentArticleText = new StringBuilder(trimmed);
            } else if (currentArticleText != null) {
                // 属于当前法条的延续内容（款、项、子项等）
                currentArticleText.append("\n").append(trimmed);
            }
        }

        // 保存最后一条
        if (currentArticleText != null) {
            segments.add(buildSegment(
                    currentArticleText.toString(), lawName,
                    currentArticleChapter, currentArticleNum));
        }

        // 如果没有解析到任何"第X条"，回退到按段落切分
        if (segments.isEmpty()) {
            return fallbackSplit(document, lawName);
        }

        return segments;
    }

    /**
     * 构建一个带上下文和元数据的 TextSegment
     */
    private TextSegment buildSegment(String text, String lawName,
                                     String chapter, String articleNum) {
        // 在文本前注入法律名称和章节，提升检索时的语义匹配度
        StringBuilder contextual = new StringBuilder();
        contextual.append("【").append(lawName).append("】");
        if (chapter != null && !chapter.isEmpty()) {
            contextual.append(" ").append(chapter);
        }
        contextual.append("\n").append(text);

        Metadata metadata = new Metadata();
        metadata.put("law_name", lawName);
        if (chapter != null && !chapter.isEmpty()) {
            metadata.put("chapter", chapter);
        }
        if (articleNum != null && !articleNum.isEmpty()) {
            metadata.put("article_number", articleNum);
        }

        return TextSegment.from(contextual.toString(), metadata);
    }

    /**
     * 从文档元数据中提取法律名称（即文件名去掉扩展名）
     */
    private String extractLawName(Document document) {
        String fileName = document.metadata().getString("file_name");
        if (fileName != null && !fileName.isEmpty()) {
            return fileName.replaceAll("\\.[^.]+$", "");
        }
        return "未知法律文件";
    }

    /**
     * 回退方案：对于没有"第X条"结构的文档，按段落切分（最大2000字符）
     */
    private List<TextSegment> fallbackSplit(Document document, String lawName) {
        List<TextSegment> segments = new ArrayList<>();
        String[] paragraphs = document.text().split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            if (buffer.length() + trimmed.length() > 2000 && buffer.length() > 0) {
                Metadata metadata = new Metadata();
                metadata.put("law_name", lawName);
                segments.add(TextSegment.from(
                        "【" + lawName + "】\n" + buffer, metadata));
                buffer = new StringBuilder();
            }
            if (buffer.length() > 0) buffer.append("\n");
            buffer.append(trimmed);
        }

        if (buffer.length() > 0) {
            Metadata metadata = new Metadata();
            metadata.put("law_name", lawName);
            segments.add(TextSegment.from(
                    "【" + lawName + "】\n" + buffer, metadata));
        }

        return segments;
    }
}
