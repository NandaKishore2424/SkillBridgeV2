import {
  ArrowRight,
  CheckCircle2,
  FileSpreadsheet,
  GraduationCap,
  ListChecks,
  LogIn,
  Sparkles,
  Users,
} from 'lucide-react'
import { Link, Navigate } from 'react-router-dom'

import { Badge } from '@/shared/components/ui/badge'
import { Button } from '@/shared/components/ui/button'
import { Footer, Header } from '@/shared/components/layout'
import { useAuth } from '@/shared/hooks/useAuth'
import { dashboardPathFor } from '@/shared/auth/dashboardPath'

import { FACTS } from './facts'
import { ReportPreview } from './ReportPreview'

/**
 * The public page.
 *
 * Everything on it is something the application does. That is a constraint, not
 * a slogan: the page it replaced advertised "Placement Tracking -- connect with
 * companies, track applications, and manage your placement journey", and
 * nothing in this system tracks a job application. The four capabilities below
 * each correspond to a screen a signed-in user can open, and the figures come
 * from `facts.ts`, which `facts.test.ts` checks against the AI service's config
 * and the backend's yaml.
 *
 * There is no sign-up. Accounts are created by a college administrator, so
 * every call to action leads to the same place.
 */

const CAPABILITIES = [
  {
    icon: FileSpreadsheet,
    title: 'Onboard a cohort from a spreadsheet',
    body: `Up to ${FACTS.maxImportRows.toLocaleString()} students per upload, one transaction per row. A row that fails says which row and why, the rest still land, and uploading the same file twice imports it once.`,
  },
  {
    icon: ListChecks,
    title: 'A syllabus trainers can actually grade against',
    body: 'Modules, submodules and topics per batch. A trainer opens a topic, sees every enrolled student with their current status, and applies one outcome to as many as they select.',
  },
  {
    icon: Sparkles,
    title: 'A skill gap measured, not guessed',
    body: `Each student's skills are embedded and compared against ${FACTS.corpusSize.toLocaleString()} real job descriptions. The report names the roles they are closest to and the skills those roles ask for that they do not have.`,
  },
  {
    icon: GraduationCap,
    title: 'Progress the student can see too',
    body: 'The same tree the trainer grades, from the other side: what is done, what is in progress, and what a trainer has marked as needing work.',
  },
]

const STEPS = [
  {
    title: 'Import your students',
    body: 'A college admin uploads a CSV. Everyone gets an invitation with a temporary password that expires.',
  },
  {
    title: 'Run the batches',
    body: 'Assign trainers, build the syllabus, enrol students. Trainers grade topic by topic.',
  },
  {
    title: 'Read the gap',
    body: 'Students see where they stand against real hiring requirements, and which skill to pick up next.',
  },
]

const ROLES = ['System admin', 'College admin', 'Trainer', 'Student']

export function Landing() {
  const { user, logout, isAuthenticated } = useAuth()

  // Declarative, not an effect. Navigating from inside `useEffect` renders the
  // whole marketing page first and replaces it a frame later, which is a
  // visible flash of the public site every time a signed-in user opens the
  // root URL.
  if (isAuthenticated && user) {
    return <Navigate to={dashboardPathFor(user.role)} replace />
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <Header user={undefined} onLogout={logout} />

      <main className="flex-1">
        {/* ---------------------------------------------------------- hero */}
        <section className="relative overflow-hidden border-b">
          <div
            className="pointer-events-none absolute inset-0 bg-grid opacity-40 [mask-image:radial-gradient(60%_50%_at_50%_0%,black,transparent)]"
            aria-hidden="true"
          />
          <div className="pointer-events-none absolute inset-0 bg-brand-wash" aria-hidden="true" />

          <div className="container relative py-16 sm:py-24">
            <div className="grid items-center gap-12 lg:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">
              <div className="animate-fade-up">
                <Badge variant="accent" className="mb-5 gap-1.5 py-1">
                  <Sparkles className="h-3.5 w-3.5" />
                  Skill-gap analysis over {FACTS.corpusSize.toLocaleString()} job descriptions
                </Badge>

                <h1 className="text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl">
                  Training that knows what
                  <span className="text-primary"> employers are asking for</span>
                </h1>

                <p className="mt-6 max-w-xl text-lg text-muted-foreground">
                  SkillBridge runs a college&rsquo;s training programme end to end &mdash; cohorts,
                  batches, syllabus, grading &mdash; and then measures every student&rsquo;s skills
                  against the roles they are actually applying for.
                </p>

                <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                  <Button asChild size="lg" className="px-8 text-base">
                    <Link to="/login">
                      <LogIn className="mr-2 h-5 w-5" />
                      Sign in
                    </Link>
                  </Button>
                  <Button asChild size="lg" variant="outline" className="px-8 text-base">
                    <a href="#how-it-works">
                      How it works
                      <ArrowRight className="ml-2 h-5 w-5" />
                    </a>
                  </Button>
                </div>

                <p className="mt-6 flex items-center gap-2 text-sm text-muted-foreground">
                  <CheckCircle2 className="h-4 w-4 text-success" />
                  Invite&#8209;only. Your college administrator creates your account.
                </p>
              </div>

              <ReportPreview className="animate-fade-up [animation-delay:120ms]" />
            </div>
          </div>
        </section>

        {/* ------------------------------------------------- what it does */}
        <section className="container py-16 sm:py-24">
          <div className="max-w-2xl">
            <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">
              Four things, done properly
            </h2>
            <p className="mt-4 text-lg text-muted-foreground">
              Each of these is a screen you can open, not a roadmap item.
            </p>
          </div>

          <div className="mt-12 grid gap-6 sm:grid-cols-2">
            {CAPABILITIES.map(({ icon: Icon, title, body }) => (
              <div
                key={title}
                className="group rounded-2xl border bg-card p-6 transition-colors hover:border-primary/40"
              >
                <span className="mb-4 inline-flex h-11 w-11 items-center justify-center rounded-xl bg-primary/10 text-primary transition-colors group-hover:bg-primary/15">
                  <Icon className="h-5 w-5" />
                </span>
                <h3 className="text-lg font-semibold">{title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{body}</p>
              </div>
            ))}
          </div>
        </section>

        {/* ------------------------------------------------- how it works */}
        <section id="how-it-works" className="scroll-mt-20 border-y bg-muted/40">
          <div className="container py-16 sm:py-24">
            <div className="max-w-2xl">
              <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">How it works</h2>
              <p className="mt-4 text-lg text-muted-foreground">
                Three steps, in the order a college would take them.
              </p>
            </div>

            <ol className="mt-12 grid gap-8 md:grid-cols-3">
              {STEPS.map(({ title, body }, index) => (
                <li key={title} className="relative">
                  <span className="flex h-10 w-10 items-center justify-center rounded-full border-2 border-primary/30 bg-background text-sm font-bold text-primary">
                    {index + 1}
                  </span>
                  <h3 className="mt-4 text-lg font-semibold">{title}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{body}</p>
                </li>
              ))}
            </ol>

            <div className="mt-12 flex flex-wrap items-center gap-2 border-t pt-8">
              <span className="mr-2 flex items-center gap-2 text-sm font-medium">
                <Users className="h-4 w-4 text-muted-foreground" />
                {FACTS.roles} roles, each with its own screens:
              </span>
              {ROLES.map((role) => (
                <Badge key={role} variant="secondary" className="font-normal">
                  {role}
                </Badge>
              ))}
            </div>
          </div>
        </section>

        {/* -------------------------------------------------- how it works
            under the hood: this is a portfolio project as much as a product,
            and the mechanism is the interesting part. Stated plainly and
            checkably rather than as a logo wall. */}
        <section className="container py-16 sm:py-24">
          <div className="rounded-2xl border bg-card p-8 sm:p-12">
            <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
              What the skill gap actually is
            </h2>
            <p className="mt-4 max-w-3xl text-muted-foreground">
              A student&rsquo;s skills are turned into a{' '}
              {FACTS.embeddingDimensions}&#8209;dimension vector by{' '}
              <code className="rounded bg-muted px-1.5 py-0.5 text-sm">
                {FACTS.embeddingModel}
              </code>{' '}
              and compared by cosine similarity against{' '}
              {FACTS.corpusSize.toLocaleString()} job descriptions held in Postgres with
              pgvector. The closest roles come back ranked, and the report lists the
              skills those roles name that the student does not have yet.
            </p>
            <dl className="mt-8 grid gap-6 sm:grid-cols-3">
              {[
                { term: FACTS.corpusSize.toLocaleString(), detail: 'job descriptions in the corpus' },
                { term: FACTS.embeddingDimensions.toString(), detail: 'dimensions per embedding' },
                { term: 'Cosine', detail: 'similarity, indexed in pgvector' },
              ].map(({ term, detail }) => (
                <div key={detail}>
                  <dt className="text-3xl font-bold tracking-tight text-primary">{term}</dt>
                  <dd className="mt-1 text-sm text-muted-foreground">{detail}</dd>
                </div>
              ))}
            </dl>
          </div>
        </section>

        {/* ----------------------------------------------------------- cta */}
        <section className="border-t bg-muted/40">
          <div className="container py-16 text-center sm:py-20">
            <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">
              Already have an account?
            </h2>
            <p className="mx-auto mt-4 max-w-xl text-lg text-muted-foreground">
              Sign in to pick up where you left off. If you do not have one yet, ask your
              college administrator &mdash; accounts are created from their end.
            </p>
            <Button asChild size="lg" className="mt-8 px-8 text-base">
              <Link to="/login">
                Sign in
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
          </div>
        </section>
      </main>

      <Footer />
    </div>
  )
}
