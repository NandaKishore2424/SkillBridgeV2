# Frontend Setup - Complete Summary

This document explains **everything** we did to set up the SkillBridge frontend, step by step.

---

## ✅ What We Completed

### 1. Project Creation

**Command:**
```bash
npm create vite@latest skillbridge-frontend -- --template react-ts
```

**What This Did:**
- Created React 19 + TypeScript project
- Used Vite as build tool (faster than Create React App)
- Set up basic project structure

**Why Vite?**
- ⚡ **10x faster** than Create React App
- 🔥 **Instant HMR** (Hot Module Replacement)
- 📦 **Smaller bundles** (better tree-shaking)
- 🔧 **Modern**: Uses native ES modules

**Files Created:**
- `package.json` - Dependencies & scripts
- `vite.config.ts` - Vite configuration
- `tsconfig.json` - TypeScript configuration
- `index.html` - HTML entry point
- `src/main.tsx` - React entry point
- `src/App.tsx` - Main app component

---

### 2. Installed Core Dependencies

**Routing:**
```bash
npm install react-router-dom
```
- **Purpose**: Client-side routing (navigation between pages)
- **Why**: Standard for React apps, handles URL changes

**API Client:**
```bash
npm install axios
```
- **Purpose**: HTTP client for API calls
- **Why**: Better than fetch API (interceptors, automatic JSON parsing)

**State Management:**
```bash
npm install @tanstack/react-query
```
- **Purpose**: Server state management, caching, refetching
- **Why**: Handles API calls, caching, loading states automatically

**Forms & Validation:**
```bash
npm install react-hook-form zod @hookform/resolvers
```
- **Purpose**: Form handling + schema validation
- **Why**: 
  - `react-hook-form`: Performant forms (minimal re-renders)
  - `zod`: TypeScript-first validation
  - `@hookform/resolvers`: Connects Zod with React Hook Form

**Icons:**
```bash
npm install lucide-react
```
- **Purpose**: Beautiful icon library
- **Why**: Modern, consistent icons

---

### 3. Set Up Tailwind CSS

**Installed:**
```bash
npm install -D tailwindcss postcss autoprefixer
```

**Created Files:**

**`tailwind.config.js`:**
```js
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: { extend: {} },
  plugins: [],
}
```
- **Purpose**: Tells Tailwind which files to scan for classes
- **Why**: Only processes classes found in your code (smaller CSS)

**`postcss.config.js`:**
```js
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
}
```
- **Purpose**: Processes CSS (Tailwind → regular CSS)
- **Why**: Adds vendor prefixes automatically

**Updated `src/index.css`:**
```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```
- **Purpose**: Imports Tailwind's base styles, components, utilities
- **Also Added**: shadcn/ui theme variables (CSS custom properties)

**What is Tailwind CSS?**
- **Utility-first CSS framework**
- **Example**: `<div className="flex items-center justify-between p-4">`
- **Benefits**: 
  - Fast development (no custom CSS files)
  - Consistent design system
  - Small bundle size (only used classes included)

---

### 4. Created Project Structure

**Folders Created:**
```
src/
 ├── auth/              # Authentication pages
 ├── students/          # Student pages
 ├── trainers/          # Trainer pages
 ├── admins/            # Admin pages
 ├── batches/           # Batch management
 ├── companies/         # Company browsing
 ├── placements/        # Placement tracking
 ├── feedback/          # Feedback system
 ├── shared/            # Shared code
 │   ├── components/    # Reusable UI components
 │   ├── hooks/         # Custom React hooks
 │   ├── utils/         # Helper functions
 │   └── types/         # TypeScript types
 ├── api/               # API client
 └── lib/               # Library configurations
```

**Why This Structure?**
- **Feature-Based**: Each feature has its own folder
- **Shared Code**: Common components in `shared/`
- **API Layer**: Centralized API calls in `api/`
- **Scalable**: Easy to add new features

---

### 5. Created Core Files

#### `src/lib/utils.ts`
```typescript
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
```
- **Purpose**: Merges Tailwind classes (for shadcn/ui)
- **Why**: Handles class conflicts intelligently

#### `src/api/client.ts`
```typescript
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});
```
- **Purpose**: Axios instance with base URL
- **Features**:
  - Request interceptor: Adds JWT token to headers
  - Response interceptor: Handles 401 errors (redirects to login)

**What are Interceptors?**
- **Request Interceptor**: Runs before every request
  - Adds `Authorization: Bearer <token>` header
- **Response Interceptor**: Runs after every response
  - Handles errors (e.g., 401 = token expired → redirect to login)

#### `src/api/auth.ts`
```typescript
export const login = async (credentials: LoginRequest): Promise<AuthResponse>
export const register = async (data: RegisterRequest): Promise<AuthResponse>
export const refreshToken = async (token: string): Promise<AuthResponse>
export const logout = async (): Promise<void>
```
- **Purpose**: Authentication API functions
- **Why**: Centralized API calls, type-safe

#### `src/lib/react-query.ts`
```typescript
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,  // 5 minutes
      gcTime: 10 * 60 * 1000,    // 10 minutes
      retry: 1,
    },
  },
});
```
- **Purpose**: React Query client configuration
- **Settings**:
  - `staleTime`: Data considered fresh for 5 minutes
  - `gcTime`: Cached data kept for 10 minutes
  - `retry`: Retry failed requests once

**What is React Query?**
- **Server State Management**: Handles API calls, caching, refetching
- **Features**:
  - Automatic caching
  - Background refetching
  - Loading/error states
  - Optimistic updates

#### `src/shared/types/index.ts`
```typescript
export type UserRole = 'SYSTEM_ADMIN' | 'COLLEGE_ADMIN' | 'TRAINER' | 'STUDENT';
export interface User { ... }
export interface College { ... }
export interface Batch { ... }
```
- **Purpose**: Shared TypeScript types
- **Why**: Type safety across the app, single source of truth

---

### 6. Set Up Routing & React Query

#### Updated `src/main.tsx`
```typescript
<QueryClientProvider client={queryClient}>
  <App />
</QueryClientProvider>
```
- **Purpose**: Wraps app with React Query provider
- **Why**: Makes React Query available throughout the app

#### Updated `src/App.tsx`
```typescript
<BrowserRouter>
  <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="/dashboard" element={<DashboardPage />} />
  </Routes>
</BrowserRouter>
```
- **Purpose**: Sets up client-side routing
- **Why**: Navigate between pages without full page reload

**What is React Router?**
- **Client-Side Routing**: Changes URL without page reload
- **Example**: `/login` → `/dashboard` (no page refresh)
- **Benefits**: Faster navigation, better UX

---

### 7. Configured Path Aliases

**Updated `tsconfig.app.json`:**
```json
"paths": {
  "@/*": ["./src/*"]
}
```

**Updated `vite.config.ts`:**
```typescript
resolve: {
  alias: {
    '@': path.resolve(__dirname, './src'),
  },
}
```

**What This Does:**
- Allows imports like: `import { cn } from '@/lib/utils'`
- Instead of: `import { cn } from '../../lib/utils'`
- **Benefits**: Cleaner imports, easier refactoring

---

### 8. Set Up shadcn/ui Configuration

**Created `components.json`:**
```json
{
  "style": "default",
  "tailwind": {
    "config": "tailwind.config.js",
    "css": "src/index.css",
    "baseColor": "slate",
    "cssVariables": true
  },
  "aliases": {
    "components": "@/shared/components",
    "utils": "@/lib/utils"
  }
}
```
- **Purpose**: Configuration for shadcn/ui
- **Why**: Tells shadcn where to put components, how to style them

**What is shadcn/ui?**
- **Component Library**: Pre-built, customizable components
- **Difference from MUI/Ant Design**: Components are in your codebase (not node_modules)
- **Benefits**: 
  - Fully customizable
  - Copy-paste components
  - TypeScript support
  - Accessible (built with Radix UI)

**How to Use:**
```bash
npx shadcn-ui@latest add button
npx shadcn-ui@latest add input
npx shadcn-ui@latest add card
```
- Adds components to `src/shared/components/ui/`
- You can modify them directly

---

### 9. Created Configuration Files

**`.gitignore`:**
- Ignores `node_modules/`, `dist/`, `.env` files
- **Why**: Don't commit dependencies or build files

**`README.md`:**
- Project documentation
- Setup instructions
- Project structure

**`components.json`:**
- shadcn/ui configuration

---

## 📁 Final Project Structure

```
skillbridge-frontend/
├── public/                    # Static files
├── src/
│   ├── api/                   # API client
│   │   ├── client.ts         # Axios instance
│   │   └── auth.ts           # Auth API calls
│   ├── auth/                  # Auth pages (to be created)
│   ├── students/              # Student pages (to be created)
│   ├── trainers/              # Trainer pages (to be created)
│   ├── admins/                # Admin pages (to be created)
│   ├── batches/               # Batch pages (to be created)
│   ├── companies/             # Company pages (to be created)
│   ├── placements/            # Placement pages (to be created)
│   ├── feedback/              # Feedback pages (to be created)
│   ├── shared/                # Shared code
│   │   ├── components/        # Reusable components
│   │   ├── hooks/            # Custom hooks
│   │   ├── utils/            # Utilities
│   │   └── types/            # TypeScript types
│   ├── lib/                   # Library configs
│   │   ├── utils.ts          # Utility functions
│   │   └── react-query.ts    # React Query config
│   ├── App.tsx                # Main app component
│   ├── main.tsx               # Entry point
│   └── index.css              # Global styles (Tailwind)
├── .env.development           # Dev environment variables
├── .env.production            # Prod environment variables
├── .gitignore                 # Git ignore rules
├── components.json            # shadcn/ui config
├── package.json               # Dependencies
├── tailwind.config.js         # Tailwind config
├── postcss.config.js          # PostCSS config
├── tsconfig.json              # TypeScript config
├── vite.config.ts             # Vite config
└── README.md                  # Documentation
```

---

## 🔧 Configuration Explained

### Vite Configuration

**`vite.config.ts`:**
```typescript
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
```

**What It Does:**
- `plugins: [react()]` - Enables React support
- `resolve.alias` - Path aliases (`@/` → `src/`)

**Why:**
- Path aliases make imports cleaner
- Example: `import { cn } from '@/lib/utils'` instead of `'../../lib/utils'`

---

### TypeScript Configuration

**`tsconfig.app.json`:**
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "jsx": "react-jsx",
    "strict": true,
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  }
}
```

**What It Does:**
- `target: "ES2022"` - Compiles to modern JavaScript
- `jsx: "react-jsx"` - React JSX transform
- `strict: true` - Strict type checking
- `paths` - Path aliases for TypeScript

**Why:**
- Type safety catches errors at compile-time
- Path aliases work in TypeScript too

---

### Tailwind Configuration

**`tailwind.config.js`:**
```js
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
}
```

**What It Does:**
- `content` - Files to scan for Tailwind classes
- Only classes found in these files are included in final CSS

**Why:**
- **Tree-shaking**: Unused classes are removed
- **Smaller CSS**: Only includes classes you actually use

---

## 🎯 How Everything Works Together

### Request Flow Example

**User clicks "Login" button:**

```
1. User fills form → clicks "Login"
   ↓
2. React Hook Form validates (Zod schema)
   ↓
3. Calls login() from api/auth.ts
   ↓
4. api/client.ts adds JWT token to request
   ↓
5. Axios sends POST /api/v1/auth/login
   ↓
6. Backend validates credentials
   ↓
7. Backend returns accessToken + refreshToken
   ↓
8. Frontend stores tokens in localStorage
   ↓
9. React Query caches the response
   ↓
10. User redirected to dashboard
```

### Component Structure Example

```
LoginPage (auth/LoginPage.tsx)
 ├── Uses React Hook Form (form handling)
 ├── Uses Zod (validation)
 ├── Uses shadcn/ui Button component
 ├── Calls login() from api/auth.ts
 └── Uses React Query (mutation)
```

---

## 📦 Dependencies Summary

### Production Dependencies

| Package | Purpose | Why |
|---------|---------|-----|
| `react` | UI library | Core framework |
| `react-dom` | React for web | Renders to DOM |
| `react-router-dom` | Routing | Client-side navigation |
| `axios` | HTTP client | API calls |
| `@tanstack/react-query` | State management | Server state, caching |
| `react-hook-form` | Forms | Performant form handling |
| `zod` | Validation | TypeScript-first validation |
| `@hookform/resolvers` | Form integration | Connects Zod + React Hook Form |
| `lucide-react` | Icons | Icon library |
| `clsx` | Class utilities | Conditional classes |
| `tailwind-merge` | Class merging | Merges Tailwind classes |

### Development Dependencies

| Package | Purpose |
|---------|---------|
| `vite` | Build tool |
| `typescript` | Type checking |
| `tailwindcss` | CSS framework |
| `postcss` | CSS processing |
| `autoprefixer` | CSS vendor prefixes |
| `@radix-ui/*` | UI primitives (for shadcn) |

---

## 🚀 Next Steps

### Immediate Next Steps

1. **Test Frontend Runs:**
   ```bash
   cd skillbridge-frontend
   npm run dev
   ```
   - Should start on `http://localhost:5173`
   - Should show basic routing (Login/Dashboard pages)

2. **Install shadcn/ui Components:**
   ```bash
   npx shadcn-ui@latest add button
   npx shadcn-ui@latest add input
   npx shadcn-ui@latest add card
   npx shadcn-ui@latest add form
   ```

3. **Create Authentication Context:**
   - Store user info
   - Handle login/logout
   - Protect routes

4. **Build Login Page:**
   - Form with email/password
   - Validation
   - Connect to backend API

5. **Build Dashboard:**
   - Role-based content
   - Navigation sidebar
   - Basic layout

---

## 📚 Key Concepts Explained

### 1. Vite vs Create React App

**Vite:**
- ⚡ Faster (uses native ES modules)
- 🔥 Instant HMR
- 📦 Smaller bundles

**Create React App:**
- 🐌 Slower (webpack-based)
- ⏳ Slower HMR
- 📦 Larger bundles

**We chose Vite** for better performance.

---

### 2. React Query vs Redux

**React Query:**
- ✅ Perfect for server state (API calls)
- ✅ Automatic caching
- ✅ Background refetching
- ✅ Loading/error states

**Redux:**
- ✅ Good for complex client state
- ❌ Overkill for simple apps
- ❌ More boilerplate

**We chose React Query** because we mostly manage server state (API responses).

---

### 3. React Hook Form vs Regular Forms

**React Hook Form:**
- ✅ Minimal re-renders (better performance)
- ✅ Less code
- ✅ Easy validation integration

**Regular Forms:**
- ❌ More re-renders
- ❌ More boilerplate
- ❌ Manual validation

**We chose React Hook Form** for better performance and developer experience.

---

### 4. shadcn/ui vs MUI/Ant Design

**shadcn/ui:**
- ✅ Components in your codebase (fully customizable)
- ✅ Copy-paste (not npm package)
- ✅ Modern (Radix UI + Tailwind)
- ✅ TypeScript-first

**MUI/Ant Design:**
- ❌ Components in node_modules (harder to customize)
- ❌ npm package (can't modify easily)
- ❌ More opinionated styling

**We chose shadcn/ui** for maximum flexibility and customization.

---

## ✅ Setup Checklist

- [x] Created Vite React + TypeScript project
- [x] Installed core dependencies (Router, Axios, React Query)
- [x] Installed form libraries (React Hook Form, Zod)
- [x] Set up Tailwind CSS
- [x] Created project folder structure
- [x] Created API client with interceptors
- [x] Set up React Query
- [x] Set up React Router
- [x] Created TypeScript types
- [x] Configured path aliases
- [x] Set up shadcn/ui configuration
- [x] Created .gitignore
- [x] Created README
- [ ] Test frontend runs (next step)
- [ ] Install shadcn/ui components (next step)
- [ ] Create authentication context (next step)

---

## 🎓 Learning Points

1. **Vite**: Modern build tool, much faster than CRA
2. **React Query**: Perfect for server state management
3. **React Hook Form**: Performant form handling
4. **Tailwind CSS**: Utility-first CSS (faster development)
5. **shadcn/ui**: Modern, customizable component library
6. **Path Aliases**: Cleaner imports (`@/` instead of `../../`)
7. **Axios Interceptors**: Automatic token handling, error handling

---

## 🚀 Ready to Test!

**Run the frontend:**
```bash
cd skillbridge-frontend
npm run dev
```

**Expected:**
- Server starts on `http://localhost:5173`
- You see basic routing (Login/Dashboard pages)
- No errors in console

**Next:** We'll build the login page and connect to the backend! 🎉

