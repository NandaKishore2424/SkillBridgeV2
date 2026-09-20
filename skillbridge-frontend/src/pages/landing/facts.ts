/**
 * The numbers the landing page prints.
 *
 * In one file because a marketing page is where a project quietly starts
 * lying: a figure is typed into JSX once, the system changes, and nobody ever
 * looks at the copy again. Each of these says where it comes from, and
 * `facts.test.ts` checks the ones that can be checked against the repository
 * itself rather than against this comment.
 */
export const FACTS = {
  /**
   * Industry job descriptions with embeddings in the corpus.
   *
   * Source: the 2026-09-19 database restore, verified table-by-table against
   * the Supabase capture (further-plans/START-HERE.md, "Facts this plan
   * depends on"). It is a fixed corpus -- nothing loads more.
   */
  corpusSize: 1500,

  /**
   * Dimensions per embedding.
   *
   * Source: `skillbridge-ai-service/config.py`, EMBEDDING_DIMENSIONS, which is
   * what `all-MiniLM-L6-v2` produces. The Postgres column is `vector(384)`.
   */
  embeddingDimensions: 384,

  /** Source: `skillbridge-ai-service/config.py`, EMBEDDING_MODEL_NAME. */
  embeddingModel: 'all-MiniLM-L6-v2',

  /**
   * Rows one CSV upload may carry.
   *
   * Source: the import limits enforced in the request (Phase 2), alongside the
   * 1 MB cap that answers 413.
   */
  maxImportRows: 2000,

  /** Roles the application actually distinguishes, with their own screens. */
  roles: 4,
} as const
