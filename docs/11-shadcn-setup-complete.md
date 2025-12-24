# shadcn/ui Setup - Complete Guide

This document explains how we set up shadcn/ui components globally for the SkillBridge frontend.

---

## ✅ What We Fixed

### Issue: Tailwind CSS v4 Compatibility

**Problem:**
```
[postcss] It looks like you're trying to use `tailwindcss` directly as a PostCSS plugin.
The PostCSS plugin has moved to a separate package.
```

**Root Cause:**
- Tailwind CSS v4 (4.1.18) has a different architecture
- Requires `@tailwindcss/postcss` instead of direct PostCSS plugin
- shadcn/ui is designed for Tailwind CSS v3

**Solution:**
- Downgraded to Tailwind CSS v3.4.1 (compatible with shadcn/ui)
- Updated `tailwind.config.js` with shadcn/ui theme configuration
- Installed `tailwindcss-animate` plugin

---

## 📦 Installed Components

We installed the following shadcn/ui components globally:

### Core Components
1. **Button** - Primary action buttons
2. **Input** - Text input fields
3. **Card** - Container for content sections
4. **Label** - Form labels
5. **Form** - Form wrapper with React Hook Form integration

### Selection Components
6. **Select** - Dropdown select menus
7. **Textarea** - Multi-line text input

### Overlay Components
8. **Dialog** - Modal dialogs
9. **Dropdown Menu** - Context menus

### Display Components
10. **Table** - Data tables
11. **Badge** - Status badges
12. **Avatar** - User avatars
13. **Separator** - Visual dividers
14. **Tabs** - Tabbed interfaces

---

## 📁 Component Structure

```
src/shared/components/ui/
├── button.tsx          # Button component
├── input.tsx           # Input component
├── card.tsx            # Card component
├── form.tsx            # Form component (React Hook Form integration)
├── label.tsx           # Label component
├── select.tsx          # Select dropdown
├── textarea.tsx        # Textarea component
├── dialog.tsx          # Dialog/Modal component
├── dropdown-menu.tsx   # Dropdown menu
├── table.tsx           # Table component
├── badge.tsx           # Badge component
├── avatar.tsx          # Avatar component
├── separator.tsx       # Separator component
├── tabs.tsx            # Tabs component
└── index.ts            # Global exports
```

---

## 🎯 How to Use Components

### Import from Global Export

**Recommended (Clean):**
```typescript
import { Button, Input, Card } from '@/shared/components/ui'
```

**Alternative (Direct):**
```typescript
import { Button } from '@/shared/components/ui/button'
```

### Example Usage

#### Button
```tsx
import { Button } from '@/shared/components/ui'

function MyComponent() {
  return (
    <div>
      <Button>Click Me</Button>
      <Button variant="outline">Outline</Button>
      <Button variant="destructive">Delete</Button>
      <Button size="sm">Small</Button>
      <Button size="lg">Large</Button>
    </div>
  )
}
```

#### Input
```tsx
import { Input, Label } from '@/shared/components/ui'

function MyForm() {
  return (
    <div>
      <Label htmlFor="email">Email</Label>
      <Input id="email" type="email" placeholder="Enter email" />
    </div>
  )
}
```

#### Card
```tsx
import { Card, CardHeader, CardTitle, CardContent } from '@/shared/components/ui'

function MyCard() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Card Title</CardTitle>
      </CardHeader>
      <CardContent>
        <p>Card content goes here</p>
      </CardContent>
    </Card>
  )
}
```

#### Form (with React Hook Form)
```tsx
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Form, FormField, FormItem, FormLabel, FormControl, FormMessage } from '@/shared/components/ui'
import { Input } from '@/shared/components/ui'
import { Button } from '@/shared/components/ui'

const formSchema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
})

function LoginForm() {
  const form = useForm({
    resolver: zodResolver(formSchema),
    defaultValues: {
      email: '',
      password: '',
    },
  })

  const onSubmit = (data: z.infer<typeof formSchema>) => {
    console.log(data)
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)}>
        <FormField
          control={form.control}
          name="email"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Email</FormLabel>
              <FormControl>
                <Input {...field} type="email" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <Button type="submit">Submit</Button>
      </form>
    </Form>
  )
}
```

#### Table
```tsx
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/shared/components/ui'

function DataTable() {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          <TableHead>Email</TableHead>
          <TableHead>Role</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow>
          <TableCell>John Doe</TableCell>
          <TableCell>john@example.com</TableCell>
          <TableCell>Student</TableCell>
        </TableRow>
      </TableBody>
    </Table>
  )
}
```

---

## ⚙️ Configuration Files

### `components.json`
```json
{
  "style": "default",
  "rsc": false,
  "tsx": true,
  "tailwind": {
    "config": "tailwind.config.js",
    "css": "src/index.css",
    "baseColor": "slate",
    "cssVariables": true
  },
  "aliases": {
    "components": "src/shared/components",
    "utils": "src/lib/utils",
    "ui": "src/shared/components/ui",
    "lib": "src/lib",
    "hooks": "src/shared/hooks"
  }
}
```

**Key Points:**
- `style: "default"` - Uses default shadcn/ui styling
- `baseColor: "slate"` - Base color scheme
- `cssVariables: true` - Uses CSS variables for theming
- `aliases` - Path mappings for component generation

### `tailwind.config.js`
```js
export default {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      colors: {
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        // ... more colors
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
}
```

**Key Points:**
- `darkMode: ["class"]` - Enables dark mode via class
- `colors` - Uses CSS variables from `index.css`
- `plugins: [tailwindcss-animate]` - Animation utilities

---

## 🎨 Theming

### CSS Variables (in `src/index.css`)

shadcn/ui uses CSS variables for theming:

```css
:root {
  --background: 0 0% 100%;
  --foreground: 222.2 84% 4.9%;
  --primary: 222.2 47.4% 11.2%;
  --primary-foreground: 210 40% 98%;
  /* ... more variables */
}
```

**To customize colors:**
1. Edit CSS variables in `src/index.css`
2. All components automatically use the new colors

**Dark mode:**
- Add `dark` class to `<html>` or parent element
- Dark mode variables are already defined in `index.css`

---

## 📚 Component Variants

### Button Variants
- `default` - Primary button
- `destructive` - Delete/danger actions
- `outline` - Outlined button
- `secondary` - Secondary actions
- `ghost` - Minimal button
- `link` - Link-style button

### Button Sizes
- `default` - Standard size
- `sm` - Small
- `lg` - Large
- `icon` - Square icon button

### Badge Variants
- `default` - Default badge
- `secondary` - Secondary badge
- `destructive` - Error badge
- `outline` - Outlined badge

---

## 🔧 Adding More Components

To add more shadcn/ui components:

```bash
npx shadcn@latest add [component-name]
```

**Examples:**
```bash
npx shadcn@latest add checkbox
npx shadcn@latest add switch
npx shadcn@latest add alert
npx shadcn@latest add toast
npx shadcn@latest add sheet
npx shadcn@latest add accordion
```

**After adding:**
1. Component is created in `src/shared/components/ui/`
2. Update `src/shared/components/ui/index.ts` to export it
3. Use it throughout the app

---

## 🎯 Best Practices

### 1. Always Import from Index
```typescript
// ✅ Good
import { Button, Input } from '@/shared/components/ui'

// ❌ Avoid (unless needed)
import { Button } from '@/shared/components/ui/button'
```

### 2. Use TypeScript
All components are fully typed. Use TypeScript for better DX.

### 3. Customize Components
Since components are in your codebase (not node_modules), you can:
- Modify them directly
- Add custom variants
- Extend functionality

### 4. Form Integration
Always use `Form` component with React Hook Form:
```tsx
import { Form, FormField, FormItem, FormLabel, FormControl, FormMessage } from '@/shared/components/ui'
```

### 5. Consistent Styling
Use Tailwind classes for custom styling:
```tsx
<Button className="w-full">Full Width</Button>
```

---

## 🚀 Next Steps

1. **Create Layout Components:**
   - Header/Navbar
   - Sidebar
   - Footer
   - Page wrapper

2. **Build Feature Components:**
   - Student card
   - Batch card
   - Progress indicator
   - Status badge

3. **Add More Components (as needed):**
   - Toast notifications
   - Alert dialogs
   - Data tables with sorting
   - Charts integration

---

## 📖 Resources

- **shadcn/ui Docs**: https://ui.shadcn.com
- **Component Examples**: https://ui.shadcn.com/docs/components
- **Tailwind CSS Docs**: https://tailwindcss.com/docs

---

## ✅ Setup Complete!

All shadcn/ui components are now:
- ✅ Installed and configured
- ✅ Available globally via `@/shared/components/ui`
- ✅ Themed with CSS variables
- ✅ Integrated with React Hook Form
- ✅ TypeScript-ready
- ✅ Fully customizable

**You can now use these components throughout the SkillBridge frontend!** 🎉

