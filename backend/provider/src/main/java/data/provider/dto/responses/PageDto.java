package data.provider.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageDto<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int number;

    /**
     * Maps a Spring Data Page of entities into a PageDto of DTOs.
     *
     * @param page    The Spring Data Page
     * @param mapper  A function to map the entity to the DTO
     * @param <E>     Entity type
     * @param <D>     DTO type
     * @return A PageDto containing the mapped DTOs and pagination metadata
     */
    public static <E, D> PageDto<D> of(final Page<E> page, final Function<E, D> mapper) {
        return PageDto.<D>builder()
                .content(page.getContent().stream().map(mapper).toList())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .number(page.getNumber())
                .build();
    }
    
    /**
     * Creates a PageDto from an existing list and page metadata.
     */
    public static <D> PageDto<D> of(final Page<?> page, final List<D> content) {
        return PageDto.<D>builder()
                .content(content)
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .number(page.getNumber())
                .build();
    }
}
