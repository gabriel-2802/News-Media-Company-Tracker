package data.provider.services;

import data.provider.dto.responses.PageDto;
import data.provider.dto.responses.TopicDto;
import data.provider.repositories.ArticleRepository;
import data.provider.repositories.TopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TopicService {

    private final TopicRepository topicRepository;
    private final ArticleRepository articleRepository;

    public boolean exists(final String name) {
        return topicRepository.existsByName(name);
    }

    public PageDto<TopicDto> getAllTopics(final int page, final int count, final String sortBy) {
        final PageRequest pageRequest = PageRequest.of(page, count);
        final Page<data.provider.models.Topic> topicPage;

        if ("name".equalsIgnoreCase(sortBy)) {
            topicPage = topicRepository.findAllSortedByName(pageRequest);
        } else {
            // Default to sorting by article count descending
            topicPage = topicRepository.findAllSortedByCount(pageRequest);
        }

        return PageDto.of(topicPage, topic -> new TopicDto(topic, articleRepository.countByTopic(topic.getName())));
    }
}
