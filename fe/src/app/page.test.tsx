import { act, render, screen } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import Page from './page';

afterEach(() => {
  vi.useRealTimers();
});

describe('Page', () => {
  it('renders the ZANI heading', () => {
    render(<Page />);
    expect(screen.getByText('ZANI')).toBeInTheDocument();
    expect(screen.getByText('수업의 흐름을 함께 연결해요')).toBeInTheDocument();
  });

  it('advances the showcase every four seconds', () => {
    vi.useFakeTimers();
    render(<Page />);

    expect(screen.getByText('수업의 흐름을 함께 연결해요')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(4_000);
    });

    expect(screen.getByText('집중 흐름은 브라우저 안에서')).toBeInTheDocument();
  });
});
