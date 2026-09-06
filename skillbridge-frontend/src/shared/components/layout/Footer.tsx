/**
 * Footer Component
 * 
 * Simple footer with copyright and links
 */

import { Link } from 'react-router-dom'

export function Footer() {
  const currentYear = new Date().getFullYear()

  return (
    <footer className="border-t bg-background">
      <div className="container mx-auto px-4 py-6">
        <div className="flex flex-col items-center justify-between gap-4 md:flex-row">
          <div className="text-center md:text-left">
            <p className="text-sm text-muted-foreground">
              © {currentYear} SkillBridge. All rights reserved.
            </p>
          </div>
          {/*
            Privacy and Terms links removed 2026-09-06 -- neither page exists,
            so both were dead routes. Legal pages are worth having before this is
            shown to real colleges; a link to a page that silently does nothing
            is not.
          */}
        </div>
      </div>
    </footer>
  )
}

