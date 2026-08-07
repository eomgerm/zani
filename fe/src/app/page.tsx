'use client';

import Autoplay from 'embla-carousel-autoplay';
import useEmblaCarousel from 'embla-carousel-react';
import Image from 'next/image';
import { useCallback, useEffect, useState } from 'react';
import styles from './landing.module.css';

type AssetPanelProps = {
  src: string;
  label: string;
  className?: string;
  position?: string;
  imageElement?: boolean;
};

const showcaseCards = [
  {
    eyebrow: '실시간 강의',
    title: '실시간으로 강의에 참여해요',
    copy: '강사와 학생이 같은 순간을 공유하며 온라인 수업에 자연스럽게 참여합니다.',
    src: '/asset/showcase-live-classroom-v3.png',
  },
    {
    eyebrow: '학생 집중도 확인',
    title: '놓친 순간에는 짧게 알려요',
    copy: '학생은 부담 없는 응답으로 이해 상태를 표현하고 배움의 흐름을 이어갑니다.',
    src: '/asset/showcase-student-checkin-v3.png',
  },
  {
    eyebrow: '안전한 온디바이스 AI',
    title: '집중 흐름은 브라우저 안에서',
    copy: '원본 카메라 영상을 서버에 보내지 않고 학생의 집중도를 분석합니다.',
    src: '/asset/showcase-browser-analysis-v3.png',
  },
  {
    eyebrow: '강사 코칭',
    title: '필요한 순간, 수업 팁을 전해요',
    copy: '학생들의 집중도를 바탕으로 강의 개선 방안을 제안합니다.',
    src: '/asset/showcase-instructor-coaching-v3.png',
  },
  {
    eyebrow: '개인화 리포트',
    title: '수업 기록을 다음 행동으로',
    copy: '강사와 학생의 맞춤형 리포트로 수업 이후까지 연결합니다.',
    src: '/asset/showcase-after-report-v3.png',
  },
];

const duringCards = [
  {
    number: '01',
    title: '학생 집중 흐름 분석',
    copy: '브라우저 안에서 학생의 영상을 분석해 집중 흐름을 살핍니다. 학생의 원본 카메라 영상은 분석 목적으로 서버에 전송하거나 저장하지 않습니다.',
    src: '/asset/landing-browser-analysis.png',
  },
  {
    number: '02',
    title: '학생에게 확인 질문',
    copy: '집중 저하를 발견한 순간에 학생에게 확인 질문을 보내 수업의 흐름을 놓치지 않도록 돕습니다.',
    src: '/asset/during-student-prompt.png',
  },
  {
    number: '03',
    title: '강사에게 수업 팁 전송',
    copy: '여러 학생에게 비슷한 집중 저하 이벤트가 모이면 집단 흐름을 바탕으로 설명 보완이나 확인 질문 같은 수업 팁을 전달합니다.',
    src: '/asset/during-instructor-tip.png',
  },
];

const afterSlides = [
  {
    role: '강사용',
    eyebrow: '강사에게는',
    title: '수업 전체를 돌아보는 학생 집단 리포트를',
    copy: '집단 집중 흐름과 확인이 필요했던 구간을 시간순으로 정리하고, 다음 수업에서 활용할 수 있는 설명·상호작용 인사이트를 함께 제안합니다. 분야별 평가를 통해 보완점을 확인할 수 있습니다.',
    src: '/asset/after-instructor-report.png',
  },
  {
    role: '학생용',
    eyebrow: '학생에게는',
    title: '수업 집중 흐름에서 이어지는 개인 복습 리포트를',
    copy: '전반적인 수업 참여 평가를 확인하여 다음 수업을 준비할 수 있습니다. 나의 집중 흐름과 수업 참여 이벤트를 기반으로 복습 구간과 이유까지 제안합니다. 간단한 개념 확인 퀴즈를 통해 수업 이해도를 점검하고 학습 계획을 세울 수 있습니다.',
    src: '/asset/after-student-report.png',
  },
];

function AssetPanel({ src, label, className = '', position = 'center', imageElement = false }: AssetPanelProps) {
  const isVideo = src.endsWith('.webm') || src.endsWith('.mp4');

  return (
    <div
      className={`${styles.assetPanel} ${className}`}
      style={{
        backgroundPosition: position,
        backgroundImage: isVideo || imageElement
          ? 'linear-gradient(145deg, #eaf7f2, #ffffff 58%, #dcefe8)'
          : `url('${src}'), linear-gradient(145deg, #eaf7f2, #ffffff 58%, #dcefe8)`,
      }}
      role={imageElement ? undefined : 'img'}
      aria-label={imageElement ? undefined : label}
    >
      {isVideo ? (
        <video autoPlay muted loop playsInline preload="metadata" aria-hidden="true">
          <source src={src} type={src.endsWith('.mp4') ? 'video/mp4' : 'video/webm'} />
        </video>
      ) : imageElement ? (
        <Image src={src} alt={label} width={1536} height={1024} sizes="(max-width: 720px) 94vw, 790px" loading="eager" />
      ) : null}
    </div>
  );
}

type ReportTourProps = {
  role: string;
  src: string;
  label: string;
};

function ReportTour({ role, src, label }: ReportTourProps) {
  const isStudent = role === '학생용';

  return (
    <div className={`${styles.assetPanel} ${styles.afterAsset} ${isStudent ? styles.studentReportTour : styles.instructorReportTour}`}>
      <Image
        src={src}
        alt={label}
        fill
        sizes="(max-width: 720px) 100vw, 55vw"
        className={styles.reportCapture}
      />
      {isStudent ? (
        <>
          <Image
            src="/asset/after-student-quiz-result.png"
            alt="학생용 퀴즈 결과 이미지"
            fill
            sizes="(max-width: 720px) 100vw, 55vw"
            className={styles.quizResultCapture}
          />
          <span className={styles.reportPointer} aria-hidden="true">
            <svg viewBox="0 0 28 36">
              <path d="M3 2.5V31l7-7 4.8 9.2 4.7-2.5-4.6-8.7H26L3 2.5Z" fill="#111827" stroke="#fff" strokeWidth="2" strokeLinejoin="round" />
            </svg>
          </span>
        </>
      ) : null}
    </div>
  );
}

export default function Home() {
  const [showcaseIndex, setShowcaseIndex] = useState(0);
  const [afterIndex, setAfterIndex] = useState(0);
  const [autoplay] = useState(() => Autoplay({
    delay: 5_500,
    stopOnInteraction: false,
    stopOnMouseEnter: true,
    breakpoints: { '(prefers-reduced-motion: reduce)': { active: false } },
  }));
  const [showcaseRef, showcaseApi] = useEmblaCarousel(
    {
      loop: true,
      align: 'center',
      duration: 45,
      breakpoints: { '(prefers-reduced-motion: reduce)': { duration: 0 } },
    },
    [autoplay],
  );

  useEffect(() => {
    if (!showcaseApi) return;

    const updateIndex = () => setShowcaseIndex(showcaseApi.selectedScrollSnap());
    updateIndex();
    showcaseApi.on('select', updateIndex).on('reInit', updateIndex);

    return () => {
      showcaseApi.off('select', updateIndex).off('reInit', updateIndex);
    };
  }, [showcaseApi]);

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    const timeout = window.setTimeout(
      () => setAfterIndex((current) => (current + 1) % afterSlides.length),
      afterIndex === 0 ? 26_000 : 30_000,
    );

    return () => window.clearTimeout(timeout);
  }, [afterIndex]);

  const moveShowcase = useCallback((direction: number) => {
    if (direction < 0) showcaseApi?.scrollPrev();
    else showcaseApi?.scrollNext();
    autoplay.reset();
  }, [autoplay, showcaseApi]);

  const selectShowcase = useCallback((index: number) => {
    showcaseApi?.scrollTo(index);
    autoplay.reset();
  }, [autoplay, showcaseApi]);

  const moveAfter = (direction: number) => {
    setAfterIndex((current) => (current + direction + afterSlides.length) % afterSlides.length);
  };

  const afterSlide = afterSlides[afterIndex];

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <nav className={styles.nav} aria-label="주요 메뉴">
          <Image src="/brand/zani-logo.png" alt="ZANI 로고" aria-hidden="true" width={80} height={30} />
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
          <Image
            className={styles.heroBrand}
            src="/brand/zani-logo.png"
            alt="ZANI"
            width={250}
            height={93}
            priority
          />
          <p>
            ZANI는 실시간 온라인 수업에서 학생들의 집중 저하를 찾아 강사에게 알리고,
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
          <div className={styles.showcaseViewport} ref={showcaseRef}>
            <div className={styles.showcaseGrid}>
              {showcaseCards.map((card) => (
                <div className={styles.showcaseSlide} key={card.title}>
                  <article className={styles.showcaseCard}>
                    <AssetPanel
                      src={card.src}
                      label={`${card.title} 이미지`}
                      className={styles.showcaseAsset}
                      imageElement
                    />
                    <div className={styles.showcaseContent}>
                      <p className={styles.showcaseEyebrow}>{card.eyebrow}</p>
                      <h3>{card.title}</h3>
                      <p className={styles.showcaseCopy}>{card.copy}</p>
                    </div>
                  </article>
                </div>
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
                onClick={() => selectShowcase(index)}
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
            학생에게는 짧고 부담 없는 확인을, 강사에게는 학생들의 집단 집중 저하와 이를 보완할 수업 팁을 제공합니다.
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
            <ReportTour
              role={afterSlide.role}
              src={afterSlide.src}
              label={`${afterSlide.role} 리포트 이미지`}
            />
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

