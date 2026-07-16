import { render, screen } from '@testing-library/react';
import Page from './page';

describe('HomePage', () => {
  it('renders the route label', () => {
    render(<Page />);
    expect(screen.getByText('홈')).toBeInTheDocument();
  });
});
