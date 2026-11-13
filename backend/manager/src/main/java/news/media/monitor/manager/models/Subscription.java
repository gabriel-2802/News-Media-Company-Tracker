package news.media.monitor.manager.models;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Subscription {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionType type;

    @Column(name = "target_id", nullable = false, length = 255)
    private String targetId;

    /** TICKER only: minimum event impact score (0-1) that triggers an alert. */
    @Column(name = "min_impact")
    private Double minImpact;

    /** TICKER only: alert only for events with one of these topics; empty = any topic. */
    @Convert(converter = StringListConverter.class)
    @Column(length = 1000)
    private List<String> topics = new java.util.ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
