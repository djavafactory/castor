import { Toaster as Sonner } from 'sonner'

/** Ink pill toasts above everything but the login page and the drag chip. */
export function Toaster() {
  return (
    <Sonner
      position="bottom-center"
      offset={68}
      duration={3000}
      style={{ zIndex: 60 }}
      toastOptions={{
        classNames: {
          toast:
            '!rounded-full !bg-ink !px-4 !py-2 !text-[13px] !font-medium !text-paper !border-0 !shadow-toast',
          actionButton: '!rounded-full !bg-paper !px-3 !text-ink',
        },
      }}
    />
  )
}
