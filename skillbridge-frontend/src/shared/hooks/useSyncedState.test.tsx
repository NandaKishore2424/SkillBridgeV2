import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'

import { usePagedFilter, useSyncedState } from './useSyncedState'

/**
 * The two hooks that replaced seven `useEffect(() => setState(...))` blocks.
 *
 * The behaviour worth pinning is not "it copies the value" -- an effect does
 * that too. It is:
 *
 * - an edit **survives** an unrelated re-render, which is the whole reason the
 *   state is editable rather than derived;
 * - the reset happens **before the browser sees anything**, so there is no
 *   frame showing the previous selection;
 * - a new array or object identity does **not** reset the state, which is the
 *   trap in the effect version: `[data.map(d => d.id)]` is a new array every
 *   render and would wipe the user's edit on each keystroke elsewhere.
 */

function Selection({ source, id }: { source: string; id: number }) {
  const [value, setValue] = useSyncedState(source, id)
  const [, forceRender] = useState(0)
  return (
    <div>
      <output data-testid="value">{value}</output>
      <button onClick={() => setValue('edited')}>edit</button>
      <button onClick={() => forceRender((n) => n + 1)}>re-render</button>
    </div>
  )
}

describe('useSyncedState', () => {
  it('starts at the source', () => {
    render(<Selection source="first" id={1} />)

    expect(screen.getByTestId('value')).toHaveTextContent('first')
  })

  it('takes the new source when the key changes', () => {
    const view = render(<Selection source="first" id={1} />)

    view.rerender(<Selection source="second" id={2} />)

    expect(screen.getByTestId('value')).toHaveTextContent('second')
  })

  it('keeps an edit while the key stays the same', async () => {
    const view = render(<Selection source="first" id={1} />)

    await userEvent.click(screen.getByRole('button', { name: 'edit' }))
    // A new source object for the same thing -- a refetch that returned equal
    // data. The effect version reset the field here and lost what was typed.
    view.rerender(<Selection source="first again" id={1} />)

    expect(screen.getByTestId('value')).toHaveTextContent('edited')
  })

  it('survives an unrelated re-render', async () => {
    render(<Selection source="first" id={1} />)

    await userEvent.click(screen.getByRole('button', { name: 'edit' }))
    await userEvent.click(screen.getByRole('button', { name: 're-render' }))

    expect(screen.getByTestId('value')).toHaveTextContent('edited')
  })

  it('never commits the stale value when the key changes', () => {
    const painted: string[] = []

    function Recorder({ source, id }: { source: string; id: number }) {
      const [value] = useSyncedState(source, id)
      painted.push(value)
      return <output data-testid="value">{value}</output>
    }

    const view = render(<Recorder source="a" id={1} />)
    painted.length = 0
    act(() => view.rerender(<Recorder source="b" id={2} />))

    // React restarts the render rather than committing "a" and correcting it,
    // so the DOM never holds the old value for a frame. An effect would have
    // painted "a" first.
    expect(painted.at(-1)).toBe('b')
    expect(screen.getByTestId('value')).toHaveTextContent('b')
  })

  it('defaults the key to the source, for a primitive', () => {
    function Plain({ source }: { source: string }) {
      const [value] = useSyncedState(source)
      return <output data-testid="value">{value}</output>
    }

    const view = render(<Plain source="one" />)
    view.rerender(<Plain source="two" />)

    expect(screen.getByTestId('value')).toHaveTextContent('two')
  })
})

function Pager({ filter }: { filter: string }) {
  const [page, setPage] = usePagedFilter(filter)
  return (
    <div>
      <output data-testid="page">{page}</output>
      <button onClick={() => setPage((p) => p + 1)}>next</button>
    </div>
  )
}

describe('usePagedFilter', () => {
  it('starts on the first page', () => {
    render(<Pager filter="" />)

    expect(screen.getByTestId('page')).toHaveTextContent('0')
  })

  it('goes back to the first page when the filter changes', async () => {
    const view = render(<Pager filter="" />)
    await userEvent.click(screen.getByRole('button', { name: 'next' }))
    await userEvent.click(screen.getByRole('button', { name: 'next' }))
    expect(screen.getByTestId('page')).toHaveTextContent('2')

    view.rerender(<Pager filter="priya" />)

    // Otherwise: page 3 of a result set that now has two rows, which renders an
    // empty table and reads as "no matches" for a search that matched.
    expect(screen.getByTestId('page')).toHaveTextContent('0')
  })

  it('stays where it is while the filter is unchanged', async () => {
    const view = render(<Pager filter="priya" />)
    await userEvent.click(screen.getByRole('button', { name: 'next' }))

    view.rerender(<Pager filter="priya" />)

    expect(screen.getByTestId('page')).toHaveTextContent('1')
  })
})
