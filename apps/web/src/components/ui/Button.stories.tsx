import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import { Button } from './Button'

const meta = {
  title: 'SkoLab / Primitives / Button',
  component: Button,
  parameters: { layout: 'centered' },
  tags: ['autodocs'],
  argTypes: {
    variant: { control: 'select', options: ['signal', 'primary', 'outlined', 'ghost', 'text'] },
    size: { control: 'select', options: ['md', 'lg'] },
    loading: { control: 'boolean' },
    disabled: { control: 'boolean' },
  },
} satisfies Meta<typeof Button>

export default meta
type Story = StoryObj<typeof meta>

export const Signal: Story = { args: { variant: 'signal', fullWidth: false, children: 'Compile manuscript' } }
export const Primary: Story = { args: { variant: 'primary', fullWidth: false, children: 'Share workspace' } }
export const Outlined: Story = { args: { variant: 'outlined', fullWidth: false, children: 'Review changes' } }
export const Loading: Story = { args: { variant: 'signal', fullWidth: false, loading: true, children: 'Compiling' } }
export const Disabled: Story = { args: { variant: 'primary', fullWidth: false, disabled: true, children: 'Unavailable' } }
