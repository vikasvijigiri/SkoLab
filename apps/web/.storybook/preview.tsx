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
      return (
        <div data-theme={theme} className="min-h-screen bg-page-bg p-8 text-text-primary">
          <Story />
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
