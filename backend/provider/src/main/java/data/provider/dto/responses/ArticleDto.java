package data.provider.dto.responses;

import data.provider.models.Article;
import data.provider.models.TopicAssignment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Schema(description = "An article as stored in the system.")
public record ArticleDto(

        @Schema(description = "Byline reported by the source, if any.", example = "Jane Doe")
        String author,

        @Schema(description = "Article headline.", example = "Regional lender fined over AML controls")
        String title,

        @Schema(description = "Canonical URL of the article.", example = "https://www.bankingdive.com/news/example/")
        String url,

        @Schema(description = "Full extracted article text.")
        String bodyText,

        @Schema(description = "Timestamp the article was published by the source.", example = "2026-07-01T14:30:00")
        LocalDateTime publishedAt,

        @Schema(description = "Highest-scoring topic of this article (kept for backwards compatibility).",
                example = "regulation_legal_enforcement")
        String topic,

        @Schema(description = "All topics of this article with classifier scores, highest first.")
        List<TopicScoreDto> topics,

        @Schema(description = "Sentiment label of title + lead.", example = "negative")
        String sentimentLabel,

        @Schema(description = "Signed sentiment in [-1, 1].", example = "-0.62")
        Double sentimentScore,

        @Schema(description = "ISO 639-1 language code.", example = "en")
        String language,

        @Schema(description = "Name of the news source this article was scraped from.", example = "banking-dive")
        String source) {

    public ArticleDto(Article article) {
        this(article.getAuthor(), article.getTitle(), article.getUrl(),
                article.getBodyText(), article.getPublishedAt(),
                article.primaryTopic().map(t -> t.getTopic().getName()).orElse(null),
                article.getTopics() == null ? List.of() : article.getTopics().stream()
                        .filter(t -> t.getTopic() != null)
                        .sorted(Comparator.comparing((TopicAssignment t) -> t.getScore() != null ? t.getScore() : 0.0).reversed())
                        .map(TopicScoreDto::new)
                        .toList(),
                article.getSentimentLabel(),
                article.getSentimentScore(),
                article.getLanguage(),
                article.getNewsSource() != null ? article.getNewsSource().getName() : null);
    }
}
