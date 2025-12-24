# Frontend Setup - Complete Guide

This guide walks you through setting up the SkillBridge frontend with React + TypeScript, step by step.

---

## Frontend Tech Stack

### Core
- **React 18** - UI library
- **TypeScript** - Type safety
- **Vite** - Build tool (faster than Create React App)

### Routing & Navigation
- **React Router v6** - Client-side routing

### API & State Management
- **Axios** - HTTP client for API calls
- **TanStack Query (React Query)** - Server state management, caching, refetching

### Forms & Validation
- **React Hook Form** - Performant form handling
- **Zod** - Schema validation (works great with React Hook Form)

### UI Components & Styling
- **shadcn/ui** - Modern, customizable component library
- **Tailwind CSS** - Utility-first CSS framework (required for shadcn)
- **Lucide React** - Icon library

### Additional Tools
- **ESLint** - Code linting
- **Prettier** - Code formatting

---

## Setup Steps Overview

1. **Create React + TypeScript project with Vite**
2. **Install core dependencies** (React Router, Axios, React Query)
3. **Install UI libraries** (shadcn/ui, Tailwind CSS)
4. **Set up project structure** (folders, files)
5. **Configure environment variables**
6. **Set up API client** (Axios with interceptors)
7. **Create basic routing**
8. **Test the setup**

---

## Step-by-Step Instructions

### Step 1: Create Vite Project

**Command:**
```bash
npm create vite@latest skillbridge-frontend -- --template react-ts
```

**What this does:**
- Creates a new React + TypeScript project
- Uses Vite as build tool (much faster than Create React App)
- Sets up basic project structure

**Why Vite?**
- ⚡ **Faster**: Instant server start, fast HMR (Hot Module Replacement)
- 📦 **Smaller bundles**: Better tree-shaking
- 🔧 **Modern**: Uses native ES modules

---

### Step 2: Install Core Dependencies

**Navigate to project:**
```bash
cd skillbridge-frontend
npm install
```

**Install routing:**
```bash
npm install react-router-dom
```

**Install API client:**
```bash
npm install axios
```

**Install state management:**
```bash
npm install @tanstack/react-query
```

**Install form handling:**
```bash
npm install react-hook-form zod @hookform/resolvers
```

**What each does:**
- `react-router-dom`: Client-side routing (navigation between pages)
- `axios`: HTTP client (better than fetch API)
- `@tanstack/react-query`: Server state management, caching, automatic refetching
- `react-hook-form`: Performant form library
- `zod`: TypeScript-first schema validation
- `@hookform/resolvers`: Connects Zod with React Hook Form

---

### Step 3: Install UI Libraries (shadcn/ui + Tailwind)

**Install Tailwind CSS:**
```bash
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
```

**Install shadcn/ui:**
```bash
npx shadcn-ui@latest init
```

**Install icons:**
```bash
npm install lucide-react
```

**What each does:**
- `tailwindcss`: Utility-first CSS framework
- `shadcn/ui`: Modern, customizable component library (copy-paste components)
- `lucide-react`: Beautiful icon library

**Why shadcn/ui?**
- ✅ **Customizable**: Components are in your codebase (not node_modules)
- ✅ **Modern**: Built with Radix UI + Tailwind
- ✅ **TypeScript**: Full TypeScript support
- ✅ **Accessible**: Built-in accessibility features

---

### Step 4: Project Structure

**Create folder structure:**
```
skillbridge-frontend/
├── src/
│   ├── auth/              # Authentication pages & logic
│   ├── students/           # Student pages
│   ├── trainers/           # Trainer pages
│   ├── admins/             # Admin pages
│   ├── batches/            # Batch management
│   ├── companies/          # Company browsing
│   ├── placements/         # Placement tracking
│   ├── feedback/           # Feedback system
│   ├── shared/             # Shared code
│   │   ├── components/     # Reusable UI components
│   │   ├── hooks/          # Custom React hooks
│   │   ├── utils/          # Helper functions
│   │   └── types/          # TypeScript types
│   ├── api/                # API client
│   │   ├── client.ts       # Axios instance
│   │   ├── auth.ts         # Auth API calls
│   │   └── ...
│   ├── lib/                # Library configurations
│   │   └── utils.ts        # Utility functions (for shadcn)
│   ├── App.tsx             # Main app component
│   └── main.tsx            # Entry point
├── public/                 # Static files
└── package.json
```

---

### Step 5: Environment Variables

**Create `.env.development`:**
```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

**Create `.env.production`:**
```env
VITE_API_BASE_URL=https://api.skillbridge.com/api/v1
```

**Why:**
- Different API URLs for dev and production
- `VITE_` prefix makes it accessible in frontend code

---

### Step 6: Configure Tailwind CSS

**Update `tailwind.config.js`:**
```js
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {},
  },
  plugins: [],
}
```

**Update `src/index.css`:**
```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

---

### Step 7: Set Up API Client

**Create `src/api/client.ts`:**
- Axios instance with base URL
- Request interceptor (adds JWT token)
- Response interceptor (handles errors)

**Create `src/api/auth.ts`:**
- Login API call
- Register API call
- Refresh token API call

---

### Step 8: Set Up React Query

**Create `src/lib/react-query.ts`:**
- React Query client configuration
- Default options (staleTime, cacheTime)

**Wrap App with QueryClientProvider**

---

### Step 9: Set Up Routing

**Create routes:**
- `/login` - Login page
- `/register` - Register page
- `/dashboard` - Main dashboard (role-based)
- `/batches` - Batch management
- etc.

---

### Step 10: Create Basic Layout

**Create layout components:**
- `Layout.tsx` - Main layout with sidebar/navbar
- `AuthLayout.tsx` - Layout for auth pages (login, register)

---

## What We'll Build First

1. **Project setup** (Vite + TypeScript)
2. **Basic routing** (React Router)
3. **API client** (Axios with interceptors)
4. **Login page** (React Hook Form + shadcn components)
5. **Dashboard** (Basic layout)

---

## Next Steps After Setup

1. Create authentication context
2. Build login/register pages
3. Create protected routes
4. Build dashboard (role-based)
5. Connect to backend API

---

Let's start setting it up! 🚀

