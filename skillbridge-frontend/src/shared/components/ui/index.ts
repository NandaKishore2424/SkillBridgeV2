/**
 * Global shadcn/ui components export
 * Import components from here throughout the app
 * 
 * Example:
 * import { Button, Input, Card } from '@/shared/components/ui'
 */

// Button
export { Button, buttonVariants } from './button'

// Input
export { Input } from './input'

// Card
export {
  Card,
  CardHeader,
  CardFooter,
  CardTitle,
  CardDescription,
  CardContent,
} from './card'

// Label
export { Label } from './label'

// Select
export {
  Select,
  SelectGroup,
  SelectValue,
  SelectTrigger,
  SelectContent,
  SelectItem,
  SelectLabel,
  SelectSeparator,
} from './select'

// Textarea
export { Textarea } from './textarea'

// Dialog
export {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogFooter,
  DialogTitle,
  DialogDescription,
} from './dialog'

// Dropdown Menu
export {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
} from './dropdown-menu'

// Table
export {
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableHead,
  TableRow,
  TableCell,
  TableCaption,
} from './table'

// Badge
export { Badge, badgeVariants } from './badge'

// Avatar
export { Avatar, AvatarImage, AvatarFallback } from './avatar'

// Separator
export { Separator } from './separator'

// Tabs
export { Tabs, TabsList, TabsTrigger, TabsContent } from './tabs'

// Form
export {
  Form,
  FormItem,
  FormLabel,
  FormControl,
  FormDescription,
  FormMessage,
  FormField,
  useFormField,
} from './form'

// Scroll Area
export { ScrollArea, ScrollBar } from './scroll-area'

// Alert
export { Alert, AlertTitle, AlertDescription } from './alert'

// Radio Group
export { RadioGroup, RadioGroupItem } from './radio-group'

// Checkbox
export { Checkbox } from './checkbox'

// Skeleton
export { Skeleton } from './skeleton'
export { TableSkeleton, StatCardSkeleton, ListSkeleton, CardSkeleton, FormSkeleton } from './loading-skeleton'

// Toast
export { Toaster } from './toaster'
export { useToast, toast } from '@/shared/hooks/use-toast'
