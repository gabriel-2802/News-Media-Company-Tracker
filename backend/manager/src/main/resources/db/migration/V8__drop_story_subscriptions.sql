-- Clustering was removed: every article is its own story, so a story never gains
-- articles and a STORY subscription could never notify. The STORY type is gone
-- from the code; remove any rows of it (their notifications stay).
DELETE FROM subscriptions WHERE type = 'STORY';
