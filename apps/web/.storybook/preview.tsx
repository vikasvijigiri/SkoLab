import type { Preview } from '@storybook/nextjs-vite'
// Storybook's Vite pipeline handles this stylesheet import at runtime.
// @ts-expect-error CSS modules are provided by the bundler, not TypeScript.
import '../src/app/globals.css'

const preview: Preview = {
  globalTypes: {
    theme: {
      description: 'SkoLab color theme',
      defaultValue: 'light',
      toolbar: {
        title: 'Theme',
        icon: 'paintbrush',
        items: ['light', 'dark'],
      },
    },
  },
  decorators: [
    (Story, context) => {
      const theme = context.globals.theme === 'dark' ? 'dark' : 'light'
      const fullBleed = context.parameters.fullBleed === true
      const boxedShell = context.parameters.boxedShell === true
      const story = <Story />
      return (
        <div
          data-theme={theme}
          className={fullBleed
            ? "h-dvh overflow-hidden bg-page-bg text-text-primary"
            : "min-h-screen bg-page-bg p-8 text-text-primary"}
        >
          {boxedShell ? (
            <div className="box-border h-full w-full max-w-full p-3 sm:p-4 lg:p-6">
              <div className="hasamex-page-frame h-full max-w-full">{story}</div>
            </div>
          ) : story}
        </div>
      )
    },
  ],
  parameters: {
    controls: {
      matchers: {
       color: /(background|color)$/i,
       date: /Date$/i,
      },
    },
  },
};

export default preview;
