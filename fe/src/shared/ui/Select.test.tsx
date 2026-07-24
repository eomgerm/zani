import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Select } from './Select';

const OPTIONS = [
  { value: 'cam-1', label: '기본 카메라' },
  { value: 'cam-2', label: '외장 카메라' },
];

describe('Select', () => {
  it('값이 없으면 placeholder 를 보여준다', () => {
    render(<Select options={OPTIONS} value={null} onChange={() => {}} placeholder="카메라 없음" />);
    expect(screen.getByRole('combobox')).toHaveTextContent('카메라 없음');
  });

  it('선택된 옵션의 라벨을 보여준다', () => {
    render(<Select options={OPTIONS} value="cam-2" onChange={() => {}} />);
    expect(screen.getByRole('combobox')).toHaveTextContent('외장 카메라');
  });

  it('클릭으로 목록을 열고 옵션을 선택하면 onChange 후 닫힌다', () => {
    const onChange = vi.fn();
    render(<Select options={OPTIONS} value="cam-1" onChange={onChange} />);

    fireEvent.click(screen.getByRole('combobox'));
    expect(screen.getByRole('listbox')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('option', { name: /외장 카메라/ }));
    expect(onChange).toHaveBeenCalledWith('cam-2');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('키보드(ArrowDown + Enter)로 옵션을 선택할 수 있다', () => {
    const onChange = vi.fn();
    render(<Select options={OPTIONS} value="cam-1" onChange={onChange} />);
    const trigger = screen.getByRole('combobox');

    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(screen.getByRole('listbox')).toBeInTheDocument();

    fireEvent.keyDown(trigger, { key: 'ArrowDown' });
    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('cam-2');
  });

  it('Escape 로 목록을 닫는다', () => {
    render(<Select options={OPTIONS} value="cam-1" onChange={() => {}} />);
    const trigger = screen.getByRole('combobox');

    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: 'Escape' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('옵션이 없으면 비활성화 상태로 열리지 않는다', () => {
    render(<Select options={[]} value={null} onChange={() => {}} disabled placeholder="장치 없음" />);
    const trigger = screen.getByRole('combobox');
    expect(trigger).toBeDisabled();
    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});
