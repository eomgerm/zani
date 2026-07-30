import { render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import { AuthProvider } from '@/domains/auth';
import Page from './page';

// 홈의 참여하기가 입장 전 점검으로 이동시키므로 라우터가 필요하다. jsdom 에는 App Router 가 없다.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

describe('HomePage', () => {
  it('renders the lecture entry cards', () => {
    render(
      <AuthProvider>
        <Page />
      </AuthProvider>,
    );
    expect(screen.getByText('강의실 만들기')).toBeInTheDocument();
    expect(screen.getByText('강의실 참여하기')).toBeInTheDocument();
  });
});
