import { render, screen } from '@testing-library/react';
import Page from './page';

describe('Page', () => {
  it('renders the ZANI heading', () => {
    render(<Page />);
    expect(screen.getByText('ZANI')).toBeInTheDocument();
  });
});
