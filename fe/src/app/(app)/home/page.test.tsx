import { render, screen } from '@testing-library/react';
import Page from './page';

describe('HomePage', () => {
  it('renders the lecture entry cards', () => {
    render(<Page />);
    expect(screen.getByText('강의실 만들기')).toBeInTheDocument();
    expect(screen.getByText('강의실 참여하기')).toBeInTheDocument();
  });
});
