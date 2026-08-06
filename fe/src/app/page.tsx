'use client';

import { useEffect, useState } from 'react';
import styles from './landing.module.css';

type AssetPanelProps = {
  src: string;
  label: string;
  className?: string;
  position?: string;
};

const showcaseCards = [
  {
    eyebrow: '실시간 연결',
    title: '수업의 흐름을 함께 연결해요',
    copy: '강사와 학생이 같은 순간을 공유하며 온라인 수업에 자연스럽게 참여합니다.',
    src: '/asset/showcase-live-classroom-v3.png',
  },
  {
    eyebrow: '안전한 온디바이스 AI',
    title: '집중 흐름은 브라우저 안에서',
    copy: '원본 카메라 영상을 서버에 보내지 않고 수업 참여 신호를 분석합니다.',
    src: '/asset/showcase-browser-analysis-v3.png',
  },
  {
    eyebrow: '학생 체크인',
    title: '놓친 순간에는 짧게 알려요',
    copy: '학생은 부담 없는 응답으로 이해 상태를 표현하고 배움의 흐름을 이어갑니다.',
    src: '/asset/showcase-student-checkin-v3.png',
  },
  {
    eyebrow: '강사 코칭',
    title: '필요한 순간, 수업 팁을 전해요',
    copy: '익명 집단 신호를 바탕으로 설명 보완과 확인 질문을 제안합니다.',
    src: '/asset/showcase-instructor-coaching-v3.png',
  },
  {
    eyebrow: '수업 이후',
    title: '수업 기록을 다음 행동으로',
    copy: '강사 리포트와 학생 개인 복습 추천으로 수업 이후까지 연결합니다.',
    src: '/asset/showcase-after-report-v3.png',
  },
];

const duringCards = [
  {
    number: '01',
    title: '학생 집중 흐름 분석',
    copy: '브라우저 안에서 수업 참여 신호를 분석해 집중 흐름을 살핍니다. 학생의 원본 카메라 영상은 분석 목적으로 서버에 전송하거나 저장하지 않습니다.',
    src: '/asset/landing-browser-analysis.png',
  },
  {
    number: '02',
    title: '학생에게 확인 프롬프트',
    copy: '확인이 필요한 순간에 “이해했어요”, “헷갈려요”, “놓쳤어요”처럼 짧게 응답할 수 있어 수업의 흐름을 놓치지 않도록 돕습니다.',
    src: '/asset/during-student-prompt.png',
  },
  {
    number: '03',
    title: '강사에게 수업 팁 전송',
    copy: '여러 학생에게 비슷한 신호가 모이면 개인 정보가 아닌 익명 집단 흐름을 바탕으로 설명 보완이나 확인 질문 같은 수업 팁을 전합니다.',
    src: '/asset/during-instructor-tip.png',
  },
];

const afterSlides = [
  {
    role: '강사용',
    eyebrow: '강사에게는',
    title: '수업 전체를 돌아보는 익명 집단 리포트를',
    copy: '집단 집중 흐름과 확인이 많이 필요했던 구간을 시간순으로 정리하고, 다음 수업에서 활용할 수 있는 설명·상호작용 팁을 함께 제안합니다. 학생 개인을 식별하는 정보는 보여주지 않습니다.',
    src: '/asset/after-instructor-report.webm',
  },
  {
    role: '학생용',
    eyebrow: '학생에게는',
    title: '나의 흐름에서 이어지는 개인 복습 리포트를',
    copy: '나의 집중 흐름과 수업 중 남긴 응답을 바탕으로 다시 볼 구간과 복습 이유를 제안합니다. 추천 구간을 확인하고 간단한 개념 점검으로 학습을 이어갈 수 있습니다.',
    src: '/asset/after-student-report.webm',
  },
];

function AssetPanel({ src, label, className = '', position = 'center' }: AssetPanelProps) {
  const isVideo = src.endsWith('.webm') || src.endsWith('.mp4');

  return (
    <div
      className={`${styles.assetPanel} ${className}`}
      style={{
        backgroundPosition: position,
        backgroundImage: isVideo
          ? 'linear-gradient(145deg, #eaf7f2, #ffffff 58%, #dcefe8)'
          : `url('${src}'), linear-gradient(145deg, #eaf7f2, #ffffff 58%, #dcefe8)`,
      }}
      role="img"
      aria-label={label}
    >
      {isVideo ? (
        <video autoPlay muted loop playsInline preload="metadata" aria-hidden="true">
          <source src={src} type={src.endsWith('.mp4') ? 'video/mp4' : 'video/webm'} />
        </video>
      ) : null}
    </div>
  );
}

export default function Home() {
  const [showcaseIndex, setShowcaseIndex] = useState(0);
  const [afterIndex, setAfterIndex] = useState(0);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setShowcaseIndex((current) => (current + 1) % showcaseCards.length);
    }, 4_000);

    return () => window.clearTimeout(timer);
  }, [showcaseIndex]);

  const visibleShowcaseCards = Array.from({ length: 1 }, (_, offset) => {
    return showcaseCards[(showcaseIndex + offset) % showcaseCards.length];
  });

  const moveShowcase = (direction: number) => {
    setShowcaseIndex((current) => (current + direction + showcaseCards.length) % showcaseCards.length);
  };

  const moveAfter = (direction: number) => {
    setAfterIndex((current) => (current + direction + afterSlides.length) % afterSlides.length);
  };

  const afterSlide = afterSlides[afterIndex];

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <nav className={styles.nav} aria-label="주요 메뉴">
          <img src="/brand/zani-logo.png" alt="ZANI 로고" aria-hidden="true" width="80" height="auto" />
          <a className={styles.headerButton} href="/login">
            시작하기
          </a>
        </nav>
      </header>

      <section className={styles.hero}>
        <div className={styles.heroInner}>
          <h1>
            수업이 깨어나는 모든 순간
          </h1>
          <h1 className={styles.heroBrand}>
            ZANI
          </h1>
          <p>
            ZANI는 실시간 온라인 수업에서 확인이 필요한 집단 신호를 찾아 강사에게 알리고,
            <br />
            수업이 끝난 뒤에는 근거가 있는 개인 복습 추천과 수업 리포트를 제공합니다.
          </p>
        </div>
      </section>

      <section className={styles.showcase} aria-label="ZANI 주요 기능">
        <div className={styles.showcaseStage}>
          <button
            className={`${styles.showcaseArrow} ${styles.showcaseArrowPrevious}`}
            type="button"
            onClick={() => moveShowcase(-1)}
            aria-label="이전 기능"
          >
            ←
          </button>
          <div className={styles.showcaseViewport}>
            <div className={styles.showcaseGrid} key={showcaseIndex}>
              {visibleShowcaseCards.map((card, index) => (
                <article className={styles.showcaseCard} key={`${card.title}-${index}`}>
                  <AssetPanel
                    src={card.src}
                    label={`${card.title} 이미지`}
                    className={styles.showcaseAsset}
                  />
                  <div className={styles.showcaseContent}>
                    <p className={styles.showcaseEyebrow}>{card.eyebrow}</p>
                    <h3>{card.title}</h3>
                    <p className={styles.showcaseCopy}>{card.copy}</p>
                  </div>
                </article>
              ))}
            </div>
          </div>
          <button
            className={`${styles.showcaseArrow} ${styles.showcaseArrowNext}`}
            type="button"
            onClick={() => moveShowcase(1)}
            aria-label="다음 기능"
          >
            →
          </button>
        </div>
        <div className={styles.carouselControls}>
          <div className={styles.dots} aria-label="기능 갤러리 위치">
            {showcaseCards.map((card, index) => (
              <button
                key={card.title}
                type="button"
                className={index === showcaseIndex ? styles.activeDot : ''}
                onClick={() => setShowcaseIndex(index)}
                aria-label={`${index + 1}번째 기능 보기`}
                aria-current={index === showcaseIndex ? 'true' : undefined}
              />
            ))}
          </div>
        </div>
      </section>

      <section className={styles.section} id="during-class" aria-labelledby="during-title">
        <div className={styles.sectionHead}>
          <p>수업 중</p>
          <h2 id="during-title">배움의 흐름을 놓치지 않도록</h2>
          <span>
            학생에게는 짧고 부담 없는 확인을, 강사에게는 개인을 드러내지 않는 집단 흐름과 수업 팁을 제공합니다.
          </span>
        </div>
        <div className={styles.duringGrid}>
          {duringCards.map((card) => (
            <article className={styles.duringCard} key={card.number}>
              <AssetPanel src={card.src} label={`${card.title} 이미지`} className={styles.duringAsset} />
              <div className={styles.duringBody}>
                <span className={styles.cardNumber}>{card.number}</span>
                <h3>{card.title}</h3>
                <p>{card.copy}</p>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className={`${styles.section} ${styles.afterSection}`} aria-labelledby="after-title">
        <div className={styles.sectionHead}>
          <p>수업 이후</p>
          <h2 id="after-title">같은 수업 기록을 각자의 다음 행동으로</h2>
          <span>강사와 학생에게 필요한 내용을 각각 정리해 다음 수업과 복습으로 연결합니다.</span>
        </div>
        <div className={styles.afterGallery} aria-live="polite">
          <article
            className={`${styles.afterSlide} ${afterSlide.role === '학생용' ? styles.studentAfterSlide : ''}`}
            key={afterIndex}
          >
            <div className={styles.afterCopy}>
              <p className={styles.afterEyebrow}>{afterSlide.eyebrow}</p>
              <h3>{afterSlide.title}</h3>
              <p className={styles.afterDescription}>{afterSlide.copy}</p>
            </div>
            <AssetPanel src={afterSlide.src} label={`${afterSlide.role} 리포트 이미지`} className={styles.afterAsset} />
          </article>
          <div className={styles.afterControls}>
            <button type="button" onClick={() => moveAfter(-1)} aria-label="이전 리포트">
              ←
            </button>
            <div className={styles.afterDots}>
              {afterSlides.map((slide, index) => (
                <button
                  key={slide.role}
                  type="button"
                  className={index === afterIndex ? styles.activeAfterDot : ''}
                  onClick={() => setAfterIndex(index)}
                  aria-label={`${slide.role} 리포트 보기`}
                  aria-current={index === afterIndex ? 'true' : undefined}
                />
              ))}
            </div>
            <button type="button" onClick={() => moveAfter(1)} aria-label="다음 리포트">
              →
            </button>
          </div>
        </div>
      </section>

      <section className={styles.bottomCta}>
        <h2>수업의 흐름을 읽고</h2>
        <h2>배움의 순간을 연결하세요</h2>
        <a href="/login">지금 바로 시작하기</a>
      </section>
    </main>
  );
}

