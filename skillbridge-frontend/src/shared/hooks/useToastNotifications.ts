/**
 * Toast Notification Hook
 * 
 * Convenience hook for showing toast notifications
 * Provides consistent success/error/info messages
 */

import { toast } from './use-toast'

export const useToastNotifications = () => {
  const showSuccess = (message: string, title?: string) => {
    toast({
      title: title || 'Success',
      description: message,
      variant: 'default',
    })
  }

  const showError = (message: string, title?: string) => {
    toast({
      title: title || 'Error',
      description: message,
      variant: 'destructive',
    })
  }

  const showInfo = (message: string, title?: string) => {
    toast({
      title: title || 'Info',
      description: message,
      variant: 'default',
    })
  }

  return {
    showSuccess,
    showError,
    showInfo,
  }
}

