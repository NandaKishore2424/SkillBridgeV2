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

1. Install dependencies:
```bash
npm install
```

2. Create environment file:
```bash
cp .env.example .env.development
```

3. Update `.env.development` with your backend URL:
```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

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

## Environment Variables

Create `.env.development` or `.env.production`:

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

## Next Steps

1. Set up authentication context
2. Build login/register pages
3. Create protected routes
4. Build dashboard (role-based)
5. Connect to backend API
