import { Check, Laptop, Moon, Sun } from 'lucide-react'

import { Button } from '@/shared/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'

import { useTheme } from './useTheme'
import type { ThemePreference } from './theme'

const OPTIONS: Array<{ value: ThemePreference; label: string; icon: typeof Sun }> = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Laptop },
]

/**
 * A menu rather than a two-state switch.
 *
 * A switch can only say light or dark, so the first click throws away
 * "follow my system" for good and there is no way back to it. Three explicit
 * choices cost one extra click and keep the default reachable.
 *
 * The button reports the *resolved* theme to assistive technology, not the
 * preference: "System" tells a screen-reader user nothing about what is on the
 * screen.
 */
export function ThemeToggle({ className }: { className?: string }) {
  const { preference, theme, setPreference } = useTheme()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className={cn('text-muted-foreground hover:text-foreground', className)}
          aria-label={`Theme: ${theme}. Change theme`}
        >
          <Sun className="h-5 w-5 rotate-0 scale-100 transition-transform dark:-rotate-90 dark:scale-0" />
          <Moon className="absolute h-5 w-5 rotate-90 scale-0 transition-transform dark:rotate-0 dark:scale-100" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-40">
        {OPTIONS.map(({ value, label, icon: Icon }) => (
          <DropdownMenuItem
            key={value}
            onClick={() => setPreference(value)}
            className="cursor-pointer"
          >
            <Icon className="mr-2 h-4 w-4" />
            <span>{label}</span>
            {preference === value && <Check className="ml-auto h-4 w-4" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
