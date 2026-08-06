import { render, screen } from '@testing-library/react';
import Page from './page';

describe('Page', () => {
  it('renders the ZANI heading', () => {
    render(<Page />);
    expect(screen.getByText('ZANI')).toBeInTheDocument();
    expect(screen.getByText('집중 흐름은 브라우저 안에서')).toBeInTheDocument();
  });
});
