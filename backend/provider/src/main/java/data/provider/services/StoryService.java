package data.provider.services;

import data.provider.dto.responses.ArticleDto;
import data.provider.dto.responses.PageDto;
import data.provider.dto.responses.StoryDto;
import data.provider.exceptions.BusinessException;
import data.provider.models.Story;
import data.provider.repositories.ArticleRepository;
import data.provider.repositories.StoryRepository;
import data.provider.util.Constants;
import data.provider.util.FullTextSearchUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
/** Read side of stories. Every article gets its own story when it is saved (ArticleService). */
public class StoryService {

    /**
     * Cap on articles fetched per story when hydrating full StoryDtos (see
     * getStories). Not the same as the story-list page size — this bounds
     * how many articles come back embedded in *each* story.
     */
    private static final int MAX_ARTICLES_PER_STORY = 100;

    private final StoryRepository storyRepository;
    private final ArticleRepository articleRepository;

    public PageDto<StoryDto> getStories(final int page, final int count) {
        return PageDto.of(
                storyRepository.findAllPaginated(PageRequest.of(page, count)),
                story -> new StoryDto(story, getArticlesForStory(story.getId()))
        );
    }

    private List<ArticleDto> getArticlesForStory(final String storyId) {
        return articleRepository.findByStoryId(storyId, PageRequest.of(0, MAX_ARTICLES_PER_STORY))
                .getContent().stream().map(ArticleDto::new).toList();
    }

    public StoryDto getStoryForArticle(final String articleUrl) {
        if (!articleRepository.existsByUrl(articleUrl)) {
            throw new BusinessException(Constants.ARTICLE_DOES_NOT_EXIST_ERROR, articleUrl);
        }

        final Story story = storyRepository.findByArticleUrl(articleUrl)
                .orElseThrow(() -> new BusinessException(Constants.ARTICLE_NOT_IN_STORY_ERROR, articleUrl));

        return new StoryDto(story, getArticlesForStory(story.getId()));
    }

    public boolean existsById(final String storyId) {
        return storyRepository.existsById(storyId);
    }

    public StoryDto getStoryById(final String storyId) {
        final Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new BusinessException(Constants.STORY_DOES_NOT_EXIST_ERROR, storyId));
        return new StoryDto(story, getArticlesForStory(story.getId()));
    }

    public PageDto<StoryDto> searchStories(final String query, final int page, final int count) {
        final String fulltextQuery = FullTextSearchUtil.toPrefixQuery(query);
        return PageDto.of(storyRepository.searchByTitle(fulltextQuery, PageRequest.of(page, count)), StoryDto::new);
    }
}
