# White Screen Crash - Accordion Context Error Resolution

## Error Summary

**Date Encountered:** January 30, 2026  
**Severity:** Critical (Application Breaking)  
**Affected Component:** Batch Details Page - Syllabus Tab  
**Error Type:** React Context Error + API Proxy Configuration Issue

---

## Symptoms

### Visual Symptoms
- Page loads briefly (flash of content)
- Immediately crashes to white screen
- No error message visible to user
- Developer console shows React errors

### Console Errors

**Primary Error:**
```
Uncaught Error: 'AccordionItem' must be used within 'Accordion'
  at AccordionItem (chunk-352AT7EX.js?v=d5a57ef4:1369:30)
  at ModuleAccordion.tsx:120
```

**Secondary Error:**
```
Fetch Error: SyntaxError: Unexpected token '<', "<!doctype "... is not valid JSON
  at http://localhost:5173/api/v1/batches/1/syllabus
```

### Network Tab
- API request to `/api/v1/batches/1/syllabus` returns **200 OK**
- Response body contains HTML (`<!doctype html>`) instead of JSON
- Frontend tries to parse HTML as JSON → syntax error

---

## Root Causes

### Issue #1: Missing Vite Proxy Configuration ⚠️

**Problem:**  
The `vite.config.ts` file did not have a proxy configuration to forward `/api` requests to the backend running on port 8080.

**Impact:**  
- All API requests to `/api/*` were being handled by Vite's dev server
- Vite returned the frontend's `index.html` for unknown routes
- Frontend tried to parse HTML as JSON → crash

**Location:** `skillbridge-frontend/vite.config.ts`

**Before (Missing Proxy):**
```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
```

**After (With Proxy):**
```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

### Issue #2: Accordion Context Error ⚠️

**Problem:**  
The `ModuleAccordion` component renders `<AccordionItem>` components, but they were not wrapped in an `<Accordion>` parent component in the `SyllabusTab`.

**Impact:**  
- Radix UI's Accordion components use React Context
- `<AccordionItem>` requires context from parent `<Accordion>`
- Without the parent, React throws context error → crash

**Location:** `skillbridge-frontend/src/components/batch-management/SyllabusTab.tsx`

**Before (Missing Accordion Parent):**
```tsx
// ❌ INCORRECT - No Accordion wrapper
<div className="space-y-4">
    {modules.map((module) => (
        <ModuleAccordion
            key={module.id}
            module={module}
            batchId={batchId}
            onDelete={() => deleteMutation.mutate(module.id)}
        />
    ))}
</div>
```

**After (With Accordion Parent):**
```tsx
// ✅ CORRECT - Wrapped in Accordion
<Accordion type="multiple" className="space-y-4">
    {modules.map((module) => (
        <ModuleAccordion
            key={module.id}
            module={module}
            batchId={batchId}
            onDelete={() => deleteMutation.mutate(module.id)}
        />
    ))}
</Accordion>
```

**Also Required:** Import statement
```tsx
import { Accordion } from '@/shared/components/ui/accordion';
```

### Issue #3: Hibernate MultipleBagFetchException (Backend)

**Problem:**  
Backend repository was trying to eagerly fetch both `submodules` and `topics` collections simultaneously.

**Impact:**  
- Hibernate throws `MultipleBagFetchException`
- API returns 500 Internal Server Error
- Frontend cannot load data

**Location:** `skillbridge-backend/src/main/java/com/skillbridge/syllabus/repository/SyllabusModuleRepository.java`

**Before (Multiple Bag Fetch):**
```java
// ❌ INCORRECT - Fetching two collections
@Query("SELECT DISTINCT m FROM SyllabusModule m " +
        "LEFT JOIN FETCH m.submodules " +
        "LEFT JOIN FETCH m.topics " +  // Problem!
        "WHERE m.batch.id = :batchId")
```

**After (Single Level Fetch):**
```java
// ✅ CORRECT - Fetch only submodules
@Query("SELECT DISTINCT m FROM SyllabusModule m " +
        "LEFT JOIN FETCH m.submodules " +
        "WHERE m.batch.id = :batchId " +
        "ORDER BY m.displayOrder")
List<SyllabusModule> findByBatchIdWithSubmodulesAndTopics(@Param("batchId") Long batchId);
```

**Note:** Topics are now loaded lazily when accessed (within same transaction).

---

## Resolution Steps

### Step 1: Fix Vite Proxy Configuration

```bash
# Edit vite.config.ts
code skillbridge-frontend/vite.config.ts
```

Add the `server.proxy` configuration as shown above.

### Step 2: Wrap Modules in Accordion Parent

```bash
# Edit SyllabusTab.tsx
code skillbridge-frontend/src/components/batch-management/SyllabusTab.tsx
```

1. Add import: `import { Accordion } from '@/shared/components/ui/accordion';`
2. Replace `<div>` wrapper with `<Accordion type="multiple">`

### Step 3: Fix Backend Repository Query

```bash
# Edit SyllabusModuleRepository.java
code skillbridge-backend/src/main/java/com/skillbridge/syllabus/repository/SyllabusModuleRepository.java
```

Remove the second `LEFT JOIN FETCH` for topics.

### Step 4: Restart Development Servers

**Frontend:**
```bash
cd skillbridge-frontend
# Press Ctrl+C to stop current server
npm run dev
```

**Backend:** (if needed)
```bash
cd skillbridge-backend
# Press Ctrl+C to stop
mvn spring-boot:run
```

### Step 5: Clear Browser Cache

1. Open DevTools (F12)
2. Right-click the refresh button
3. Select "Hard Reload" or "Empty Cache and Hard Reload"

---

## Verification

After applying the fixes:

1. ✅ Navigate to `/trainer/batches/1`
2. ✅ Page loads without white screen
3. ✅ No console errors
4. ✅ API requests return JSON (not HTML)
5. ✅ Modules display correctly
6. ✅ Accordions expand/collapse properly

**Tested on:** January 30, 2026  
**Browser:** Chrome, Firefox  
**Result:** All functionality working as expected

---

## Prevention Measures

### For Future Development

1. **Always Configure Vite Proxy:**
   - Add proxy configuration immediately when starting a new frontend project
   - Document the backend port in `README.md`

2. **Understand Radix UI Context Requirements:**
   - Read Radix UI documentation for context-based components
   - Always wrap context-consuming components in their providers
   - Common culprits: Accordion, Tabs, Dialog, DropdownMenu

3. **Test Immediately After Component Integration:**
   - Don't implement multiple features before testing
   - Test in browser after every major component addition
   - Check console for errors even if page "looks fine"

4. **Hibernate Best Practices:**
   - Avoid fetching multiple `@OneToMany` collections eagerly
   - Use lazy loading for nested collections
   - Consider using `@EntityGraph` for fine-grained fetch control

5. **Monitor Network Tab:**
   - Check that API responses are actually JSON
   - Verify HTTP status codes
   - Look for unexpected HTML responses

---

## Related Errors

### Similar Context Errors in Radix UI

**Tabs:**
```
Error: 'TabsTrigger' must be used within 'Tabs'
```
**Fix:** Wrap `<TabsTrigger>` elements in `<Tabs>` parent.

**Dialog:**
```
Error: 'DialogContent' must be used within 'Dialog'  
```
**Fix:** Wrap `<DialogContent>` in `<Dialog>` parent.

**DropdownMenu:**
```
Error: 'DropdownMenuItem' must be used within 'DropdownMenu'
```
**Fix:** Wrap `<DropdownMenuItem>` in `<DropdownMenuContent>` which is in `<DropdownMenu>`.

---

## Technical Deep Dive

### Why Vite Returns HTML for API Routes

Vite's dev server has a built-in fallback mechanism:

1. Request comes in for `/api/v1/batches/1/syllabus`
2. Vite checks if it's a static asset (JS, CSS, image) → No
3. Vite checks if it's a proxy route → **No** (because proxy wasn't configured)
4. Vite assumes it's a frontend route → Returns `index.html`
5. Frontend tries to parse `<!doctype html>` as JSON → Crash

**Solution:** Configure proxy so Vite knows to forward `/api/*` to backend.

### How Radix UI Context Works

Radix UI uses React Context Pattern:

```jsx
// Accordion Provider
<Accordion>  {/* Creates context */}
  <AccordionItem />  {/* Consumes context */}
</Accordion>
```

Under the hood:
```javascript
const AccordionContext = React.createContext(null);

function Accordion({ children }) {
  const value = { /* accordion state */ };
  return (
    <AccordionContext.Provider value={value}>
      {children}
    </AccordionContext.Provider>
  );
}

function AccordionItem() {
  const context = React.useContext(AccordionContext);
  if (!context) {
    throw new Error("AccordionItem must be used within Accordion");
  }
  // Use context...
}
```

**Takeaway:** Context is NOT available across component boundaries unless explicitly passed or wrapped.

---

## Debugging Tips

### Quick Diagnosis Checklist

**For White Screen Errors:**
1. ✅ Open DevTools Console → Check for React errors
2. ✅ Check Network Tab → Verify API responses
3. ✅ Look for `SyntaxError: Unexpected token '<'` → Proxy issue
4. ✅ Look for "must be used within" → Context issue
5. ✅ Check component hierarchy → Verify wrappers

**For API Issues:**
1. ✅ Test endpoint directly in browser: `http://localhost:8080/api/v1/...`
2. ✅ Check backend console for errors
3. ✅ Verify Vite proxy is forwarding requests
4. ✅ Look at response Content-Type header

**For Context Errors:**
1. ✅ Find the error line number in stack trace
2. ✅ Check component wrapping structure
3. ✅ Verify all required parents are present
4. ✅ Check imports (make sure components are from the same library version)

---

## Summary

| Issue | Cause | Fix | Files Modified |
|-------|-------|-----|----------------|
| API returns HTML | Missing Vite proxy | Add proxy config | `vite.config.ts` |
| Accordion context error | Missing `<Accordion>` wrapper | Wrap modules in `<Accordion>` | `SyllabusTab.tsx` |
| Backend exception | Multiple bag fetch | Remove second JOIN FETCH | `SyllabusModuleRepository.java` |

**Total Files Modified:** 3  
**Total Time to Fix:** ~15 minutes  
**Difficulty:** Medium  

**Key Lesson:** Always configure development environment proxies and understand React Context requirements for component libraries.

---

**Last Updated:** January 30, 2026  
**Resolved By:** Development Team  
**Status:** ✅ Resolved and Verified
