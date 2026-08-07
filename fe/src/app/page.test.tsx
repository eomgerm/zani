import { fireEvent, render, screen } from '@testing-library/react';
import Page from './page';

describe('Page', () => {
  it('renders the ZANI brand logo', () => {
    render(<Page />);
    expect(screen.getByRole('img', { name: 'ZANI' })).toBeInTheDocument();
    expect(screen.getByText('실시간으로 강의에 참여해요')).toBeInTheDocument();
  });

  it('keeps every showcase card in one carousel track', () => {
    render(<Page />);

    expect(screen.getByText('실시간으로 강의에 참여해요')).toBeInTheDocument();
    expect(screen.getByText('집중 흐름은 브라우저 안에서')).toBeInTheDocument();
    expect(screen.getByText('놓친 순간에는 짧게 알려요')).toBeInTheDocument();
    expect(screen.getByText('필요한 순간, 수업 팁을 전해요')).toBeInTheDocument();
    expect(screen.getByText('수업 기록을 다음 행동으로')).toBeInTheDocument();
  });

  it('moves each showcase visual with its card', () => {
    render(<Page />);

    expect(screen.getByRole('img', { name: '실시간으로 강의에 참여해요 이미지' }).tagName).toBe('IMG');
  });

  it('renders the report capture as an accessible image', () => {
    render(<Page />);

    expect(screen.getByRole('img', { name: '강사용 리포트 이미지' }).tagName).toBe('IMG');
  });

  it('includes the quiz result in the student report tour', () => {
    render(<Page />);

    fireEvent.click(screen.getByRole('button', { name: '학생용 리포트 보기' }));

    expect(screen.getByRole('img', { name: '학생용 퀴즈 결과 이미지' })).toBeInTheDocument();
  });
});
