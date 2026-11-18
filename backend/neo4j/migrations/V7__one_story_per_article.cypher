// ═══════════════════════════════════════════════════════════════════════════
//  Clustering removed — every article is its own story.
//
//  The provider now creates a one-article Story when it saves an article
//  (StoryRepository.createForArticle); the clusterer worker, Qdrant and the
//  Redis decision lock are gone. Stories from the clustering era that hold
//  several articles are kept: they are valid groupings and their events stay
//  as they are.
// ═══════════════════════════════════════════════════════════════════════════

// Story subscriptions notified when an article joined a story, which no
// longer happens. (The manager drops its STORY subscriptions in its V8.)
MATCH (s:Subscription)-[:SUBSCRIBES_TO]->(:Story)
DETACH DELETE s;

// Any article left without a story (saved while the clusterer was down) gets
// its own, so it can carry events. Same shape as createForArticle.
MATCH (a:Article)
WHERE NOT (a)-[:BELONGS_TO]->(:Story)
CREATE (story:Story {id: randomUUID(), title: a.title, createdAt: datetime(), lastUpdated: datetime(),
                     articleCount: 1, sourceCount: 1, trendingScore: 0.0})
CREATE (a)-[:BELONGS_TO]->(story);
