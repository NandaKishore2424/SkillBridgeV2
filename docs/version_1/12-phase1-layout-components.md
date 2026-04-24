# Phase 1: Layout Components - Implementation Complete

This document explains the layout components we built for SkillBridge frontend.

---

## ✅ Components Created

### 1. Header Component (`Header.tsx`)

**Location:** `src/shared/components/layout/Header.tsx`

**Features:**
- **Logo/Brand**: SkillBridge logo with "SB" icon
- **Responsive Design**: Hamburger menu for mobile, full header for desktop
- **User Menu Dropdown**: 
  - Avatar with user initials
  - User name/email display
  - Role badge (System Admin, College Admin, etc.)
  - Profile link
  - Logout button
- **Authentication States**: 
  - Shows user menu when authenticated
  - Shows Login/Register buttons when not authenticated
- **Sidebar Toggle**: Hamburger menu button (only on mobile, only when sidebar is enabled)

**Props:**
```typescript
interface HeaderProps {
  sidebarOpen?: boolean
  onSidebarToggle?: () => void
  user?: {
    email: string
    role: string
    name?: string
  }
  onLogout?: () => void
  showSidebarToggle?: boolean
}
```

**Key Implementation Details:**
- Uses shadcn/ui `DropdownMenu` for user menu
- Uses `Avatar` component with fallback initials
- Responsive: hides brand text on small screens
- Sticky header (stays at top when scrolling)

---

### 2. Sidebar Component (`Sidebar.tsx`)

**Location:** `src/shared/components/layout/Sidebar.tsx`

**Features:**
- **Role-Based Navigation**: Different menu items for each role
  - **System Admin**: Dashboard, Colleges, Create College, Create Admin
  - **College Admin**: Dashboard, Batches, Companies, Trainers, Students
  - **Trainer**: Dashboard, My Batches, Students
  - **Student**: Dashboard, My Batches, My Progress, Placements
- **Active Route Highlighting**: Current route is highlighted
- **Icon Support**: Each nav item has an icon (Lucide React)
- **Responsive**: 
  - Desktop: Always visible, fixed position
  - Mobile: Overlay sidebar, toggleable
- **Scrollable**: Uses ScrollArea for long navigation lists

**Props:**
```typescript
interface SidebarProps {
  role?: UserRole
  open?: boolean
  onClose?: () => void
  mobile?: boolean
}
```

**Navigation Configuration:**
- Centralized in `sidebarConfig` object
- Easy to add/remove/modify nav items
- Icon mapping for consistent icons

**Key Implementation Details:**
- Uses `useLocation` hook to detect active route
- Active route detection handles nested routes (e.g., `/admin/batches/123` matches `/admin/batches`)
- Mobile overlay with backdrop
- Smooth transitions for open/close

---

### 3. Footer Component (`Footer.tsx`)

**Location:** `src/shared/components/layout/Footer.tsx`

**Features:**
- **Copyright**: Dynamic year
- **Links**: Privacy Policy, Terms of Service (placeholder routes)
- **Responsive**: Stacks on mobile, horizontal on desktop
- **Simple & Clean**: Minimal design

**Key Implementation Details:**
- Uses container for consistent max-width
- Responsive flex layout

---

### 4. PageWrapper Component (`PageWrapper.tsx`)

**Location:** `src/shared/components/layout/PageWrapper.tsx`

**Features:**
- **Consistent Spacing**: Standardized padding/margins
- **Max-Width Control**: Configurable max-width (sm, md, lg, xl, 2xl, full)
- **Flexible**: Can disable padding if needed
- **Centered**: Auto margins for centering

**Props:**
```typescript
interface PageWrapperProps {
  children: ReactNode
  className?: string
  maxWidth?: 'sm' | 'md' | 'lg' | 'xl' | '2xl' | 'full'
  padding?: boolean
}
```

**Usage Example:**
```tsx
<PageWrapper maxWidth="xl" padding>
  <h1>Page Title</h1>
  <p>Page content...</p>
</PageWrapper>
```

**Key Implementation Details:**
- Uses Tailwind's max-width utilities
- Configurable padding (default: true)
- Accepts additional className for custom styling

---

### 5. Layout Component (`Layout.tsx`)

**Location:** `src/shared/components/layout/Layout.tsx`

**Features:**
- **Complete Layout Wrapper**: Combines Header, Sidebar, Footer, and main content
- **Responsive Sidebar Management**: 
  - Detects mobile/desktop
  - Manages sidebar open/close state
  - Handles window resize events
- **Conditional Rendering**: 
  - Shows sidebar only when authenticated and `showSidebar` is true
  - Shows footer conditionally
- **Main Content Area**: 
  - Scrollable
  - Offset for sidebar on desktop (ml-64 = 256px)

**Props:**
```typescript
interface LayoutProps {
  children: React.ReactNode
  user?: {
    email: string
    role: UserRole
    name?: string
  }
  onLogout?: () => void
  showSidebar?: boolean
  showFooter?: boolean
}
```

**Key Implementation Details:**
- Uses `useState` and `useEffect` for responsive behavior
- Desktop sidebar is always visible (not toggleable)
- Mobile sidebar is overlay with backdrop
- Main content adjusts margin based on sidebar visibility

---

## 📁 File Structure

```
src/shared/components/layout/
├── Header.tsx          # Top navigation bar
├── Sidebar.tsx         # Side navigation (role-based)
├── Footer.tsx          # Footer component
├── PageWrapper.tsx     # Page container wrapper
├── Layout.tsx          # Main layout wrapper
└── index.ts            # Centralized exports
```

---

## 🎯 Design Principles Applied

### 1. **Separation of Concerns**
- Each component has a single responsibility
- Layout components are separate from business logic
- Navigation configuration is centralized

### 2. **Reusability**
- Components accept props for flexibility
- No hardcoded values
- Configurable behavior

### 3. **Responsive Design**
- Mobile-first approach
- Breakpoints: `lg` (1024px) for desktop
- Sidebar transforms to overlay on mobile

### 4. **Type Safety**
- Full TypeScript support
- Proper interface definitions
- Type-safe props

### 5. **Accessibility**
- Semantic HTML
- ARIA labels where needed
- Keyboard navigation support (via shadcn/ui)

### 6. **Performance**
- Efficient re-renders (React hooks)
- Lazy loading ready
- Minimal dependencies

---

## 🔧 Technical Decisions

### 1. **State Management**
- Local state for sidebar open/close
- Props for user data (will be connected to AuthContext in Phase 2)
- No global state needed for layout

### 2. **Responsive Breakpoint**
- Chose `lg` (1024px) as desktop breakpoint
- Standard Tailwind breakpoint
- Good balance for tablet/desktop

### 3. **Sidebar Width**
- Fixed width: 256px (w-64)
- Standard sidebar width
- Main content offset matches sidebar width

### 4. **Icon Library**
- Lucide React (already installed)
- Consistent icon set
- Tree-shakeable (only imports used icons)

### 5. **Component Library**
- shadcn/ui components throughout
- Consistent styling
- Accessible by default

---

## 📝 Usage Examples

### Basic Layout Usage

```tsx
import { Layout } from '@/shared/components/layout'

function App() {
  const user = {
    email: 'admin@example.com',
    role: 'SYSTEM_ADMIN' as UserRole,
    name: 'John Doe'
  }

  return (
    <Layout
      user={user}
      onLogout={() => console.log('Logout')}
      showSidebar={true}
    >
      <PageWrapper>
        <h1>Dashboard</h1>
        <p>Content goes here...</p>
      </PageWrapper>
    </Layout>
  )
}
```

### Header Only (Public Pages)

```tsx
import { Header } from '@/shared/components/layout'

function LandingPage() {
  return (
    <div>
      <Header />
      <main>
        {/* Landing page content */}
      </main>
    </div>
  )
}
```

### PageWrapper Usage

```tsx
import { PageWrapper } from '@/shared/components/layout'

function MyPage() {
  return (
    <PageWrapper maxWidth="lg" padding>
      <h1>Page Title</h1>
      <p>Content with consistent spacing</p>
    </PageWrapper>
  )
}
```

---

## 🎨 Styling Details

### Color Scheme
- Uses shadcn/ui theme variables
- Supports light/dark mode (via CSS variables)
- Consistent with design system

### Spacing
- Header height: 64px (h-16)
- Sidebar width: 256px (w-64)
- Page padding: 16px mobile, 24px desktop (px-4, sm:px-6, lg:px-8)

### Typography
- Uses default font stack
- Consistent font sizes via Tailwind
- Proper heading hierarchy

---

## ✅ Testing Checklist

- [x] Header renders correctly
- [x] Sidebar shows correct navigation for each role
- [x] Active route highlighting works
- [x] Mobile sidebar toggle works
- [x] User menu dropdown works
- [x] Footer renders correctly
- [x] PageWrapper provides consistent spacing
- [x] Layout combines all components correctly
- [x] Responsive behavior works on mobile/desktop
- [x] No TypeScript errors
- [x] No linting errors

---

## 🚀 Next Steps (Phase 2)

1. **Authentication Context**: Connect Layout to AuthContext
2. **Protected Routes**: Use Layout in protected route wrapper
3. **Public Pages**: Use Header only for landing/login/register
4. **Role-Based Routing**: Implement route guards

---

## 📚 Key Learnings

1. **Component Composition**: Layout is built from smaller, focused components
2. **Responsive Patterns**: Mobile overlay sidebar is a common pattern
3. **State Management**: Local state is sufficient for UI-only concerns
4. **Type Safety**: TypeScript interfaces make components self-documenting
5. **Reusability**: Props make components flexible and reusable

---

## 🎉 Phase 1 Complete!

All layout components are:
- ✅ Built with best practices
- ✅ Fully typed with TypeScript
- ✅ Responsive and accessible
- ✅ Using shadcn/ui components
- ✅ Ready for integration with authentication

**Ready to proceed to Phase 2: Authentication Context & State Management!**

