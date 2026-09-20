import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { FACTS } from './facts'

/**
 * The landing page's numbers, checked against the code that produces them.
 *
 * A marketing page is where a project quietly starts lying. Somebody types
 * "384-dimensional embeddings" into JSX, the model is swapped a year later, and
 * the sentence is still there -- nothing renders differently, no test fails,
 * and the first person to notice is an interviewer reading the source.
 *
 * So the two figures with a single source of truth in this repository are read
 * out of that source. The rest carry their provenance in `facts.ts` and cannot
 * be checked from here; they are listed below so it is obvious which is which.
 */

const REPO = resolve(__dirname, '../../../..')
const AI_CONFIG = resolve(REPO, 'skillbridge-ai-service/config.py')
const BACKEND_YAML = resolve(REPO, 'skillbridge-backend/src/main/resources/application.yaml')

/**
 * Skips rather than fails when run outside the monorepo.
 *
 * The frontend is buildable on its own, and a CI job that checks out only this
 * directory should not go red for a file it was never given. Erroring on a
 * *present but disagreeing* file is the case that matters.
 */
const inMonorepo = existsSync(AI_CONFIG) && existsSync(BACKEND_YAML)

describe.runIf(inMonorepo)('the numbers on the landing page', () => {
  it('quotes the AI service\'s real embedding size', () => {
    const config = readFileSync(AI_CONFIG, 'utf8')
    const dimensions = config.match(/EMBEDDING_DIMENSIONS:\s*int\s*=\s*(\d+)/)

    expect(dimensions, 'EMBEDDING_DIMENSIONS moved or was renamed').not.toBeNull()
    expect(Number(dimensions![1])).toBe(FACTS.embeddingDimensions)
  })

  it('names the model the AI service actually loads', () => {
    const config = readFileSync(AI_CONFIG, 'utf8')

    expect(config).toContain(FACTS.embeddingModel)
  })

  it('quotes the import limit the backend enforces', () => {
    const yaml = readFileSync(BACKEND_YAML, 'utf8')
    const maxRows = yaml.match(/max-rows:\s*\$\{IMPORT_MAX_ROWS:(\d+)\}/)

    expect(maxRows, 'app.import.max-rows moved or was renamed').not.toBeNull()
    expect(Number(maxRows![1])).toBe(FACTS.maxImportRows)
  })
})

describe('the numbers with no machine-readable source', () => {
  it('states the corpus size the 2026-09-19 restore was verified at', () => {
    // Not derivable from the repository: the corpus lives in the database, and
    // the only copy of it is the owner's backup. START-HERE records the
    // verification. Asserting the constant here at least makes a silent edit to
    // the page fail this test and force the reasoning back into view.
    expect(FACTS.corpusSize).toBe(1500)
  })

  it('states four roles, which is what the sidebar builds menus for', () => {
    expect(FACTS.roles).toBe(4)
  })
})
