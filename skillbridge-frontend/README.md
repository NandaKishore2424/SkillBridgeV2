# SkillBridge Frontend

Frontend application for SkillBridge - Training Management Platform

## Tech Stack

- **React 19** + **TypeScript** - UI library with type safety
- **Vite** - Fast build tool
- **React Router** - Client-side routing
- **TanStack Query** - Server state management
- **Axios** - HTTP client
- **React Hook Form** + **Zod** - Form handling & validation
- **Tailwind CSS** - Utility-first CSS
- **shadcn/ui** - Modern component library
- **Lucide React** - Icon library

## Getting Started

### Prerequisites

- Node.js 18+ and npm

### Installation

Install dependencies:
```bash
npm install
```

No environment file is needed. The client calls `/api/v1` on its own origin:
in development Vite proxies `/api` to the backend on `:8080`
(`vite.config.ts`), and on Vercel a rewrite does the same (`VERCEL.md`). Keep
it relative -- an absolute `VITE_API_BASE_URL` makes the refresh cookie
cross-site and ends every session at the first token expiry.

### Development

Run the development server:
```bash
npm run dev
```

The app will be available at `http://localhost:5173`

### Build

Build for production:
```bash
npm run build
```

### Project Structure

```
src/
 ├── auth/           # Authentication pages & logic
 ├── students/       # Student pages
 ├── trainers/       # Trainer pages
 ├── admins/         # Admin pages
 ├── batches/        # Batch management
 ├── companies/      # Company browsing
 ├── placements/     # Placement tracking
 ├── feedback/       # Feedback system
 ├── shared/         # Shared code
 │   ├── components/ # Reusable UI components
 │   ├── hooks/      # Custom React hooks
 │   ├── utils/      # Helper functions
 │   └── types/      # TypeScript types
 ├── api/            # API client
 │   ├── client.ts   # Axios instance
 │   └── auth.ts     # Auth API calls
 └── lib/            # Library configurations
```

## Checks that fail the build

| Test | Fails when |
|---|---|
| `src/shared/design/tokens.test.ts` | a colour pair drops below WCAG AA, or a token exists in one theme only |
| `src/shared/rules/routes.test.ts` | a `<Link>` points at a path with no route |
| `src/pages/landing/facts.test.ts` | a figure on the landing page disagrees with the backend or AI service config |
| `src/api/client.test.ts` | the API base URL stops being relative |

```bash
npm test
npm run lint      # --max-warnings=0
```
