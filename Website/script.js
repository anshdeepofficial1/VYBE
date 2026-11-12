(() => {
  const root = document.documentElement;
  const body = document.body;
  const themeToggle = document.getElementById('themeToggle');
  const menuButton = document.getElementById('menuButton');
  const mobileNav = document.getElementById('mobileNav');
  const backToTop = document.getElementById('backToTop');
  const toast = document.getElementById('toast');

  const releaseApi = 'https://api.github.com/repos/anshdeepofficial1/VYBE/releases/latest';
  const releaseFallback = 'https://github.com/anshdeepofficial1/VYBE/releases/latest';
  const defaultVersion = 'Latest';

  const systemDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  const storedTheme = localStorage.getItem('vybe-theme');
  root.dataset.theme = storedTheme || (systemDark ? 'dark' : 'light');

  function updateThemeMeta() {
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', root.dataset.theme === 'dark' ? '#171719' : '#f4f2ef');
  }

  function renderThemeIcon() {
    const icon = document.querySelector('[data-theme-icon]');
    if (!icon) return;
    const path = icon.querySelector('path');
    if (!path) return;
    if (root.dataset.theme === 'dark') {
      path.setAttribute('d', 'M12 3a1 1 0 0 1 1 1v1.4a1 1 0 1 1-2 0V4a1 1 0 0 1 1-1Zm0 13.6a1 1 0 0 1 1 1V20a1 1 0 1 1-2 0v-2.4a1 1 0 0 1 1-1Zm8-5.6a1 1 0 0 1 0 2h-2.4a1 1 0 1 1 0-2H20ZM6.4 12a1 1 0 0 1-1 1H3a1 1 0 1 1 0-2h2.4a1 1 0 0 1 1 1Zm9.26-5.86a1 1 0 0 1 1.42 0l1 1a1 1 0 0 1-1.42 1.42l-1-1a1 1 0 0 1 0-1.42ZM5.92 15.9a1 1 0 0 1 1.42 0l1 1a1 1 0 1 1-1.42 1.42l-1-1a1 1 0 0 1 0-1.42Zm12.16 1.42a1 1 0 0 1-1.42 0l-1-1a1 1 0 1 1 1.42-1.42l1 1a1 1 0 0 1 0 1.42ZM8.34 7.16a1 1 0 0 1-1.42 0l-1-1a1 1 0 0 1 1.42-1.42l1 1a1 1 0 0 1 0 1.42ZM12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10Z');
    } else {
      path.setAttribute('d', 'M12.5 2.2a9.8 9.8 0 1 0 9.3 13 8.2 8.2 0 0 1-9.3-13Z');
    }
  }

  updateThemeMeta();
  renderThemeIcon();

  themeToggle?.addEventListener('click', () => {
    root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem('vybe-theme', root.dataset.theme);
    updateThemeMeta();
    renderThemeIcon();
  });

  menuButton?.addEventListener('click', () => {
    const open = mobileNav?.classList.toggle('is-open');
    menuButton.setAttribute('aria-expanded', String(!!open));
    body.classList.toggle('menu-open', !!open);
  });

  window.addEventListener('resize', () => {
    if (window.innerWidth > 860 && mobileNav?.classList.contains('is-open')) {
      mobileNav.classList.remove('is-open');
      menuButton?.setAttribute('aria-expanded', 'false');
      body.classList.remove('menu-open');
    }
  });

  const currentPage = location.pathname.split('/').pop() || 'index.html';
  document.querySelectorAll('[data-nav]').forEach((link) => {
    const href = link.getAttribute('href') || '';
    const active = href === currentPage || (currentPage === '' && href === 'index.html');
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current', 'page');
  });

  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduceMotion && 'IntersectionObserver' in window) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: .12, rootMargin: '0px 0px -40px 0px' });
    document.querySelectorAll('.reveal').forEach((el) => observer.observe(el));
  } else {
    document.querySelectorAll('.reveal').forEach((el) => el.classList.add('visible'));
  }

  const screenData = {
    home: {
      eyebrow: 'Made for your VYBE',
      title: 'Home',
      description: 'Personalized discovery with Quick Picks, mixes and recommendations based on your listening.'
    },
    search: {
      eyebrow: 'Fast discovery',
      title: 'Search',
      description: 'Find songs, albums, artists and releases quickly with a clean Material 3 discovery experience.'
    },
    player: {
      eyebrow: 'Synced playback',
      title: 'Player',
      description: 'Immersive playback with synced lyrics, queue controls and a focused now-playing experience.'
    }
  };

  const previewImage = document.getElementById('previewImage');
  const previewTitle = document.getElementById('previewTitle');
  const previewDescription = document.getElementById('previewDescription');
  const previewTabs = [...document.querySelectorAll('[data-preview]')];
  const previews = {
    home: ['Discover music', 'Quick Picks, new releases and recommendations based on your listening.', 'VYBE home screen'],
    search: ['Find your next favorite', 'Search the YouTube Music catalog for songs, albums and artists.', 'VYBE search screen'],
    player: ['Now playing', 'Artwork, synchronized lyrics and queue controls, all within reach.', 'VYBE full-screen music player']
  };
  previewTabs.forEach(tab => tab.addEventListener('click', () => {
    const key = tab.dataset.preview;
    if (!previews[key] || !previewImage) return;
    const [title, description, alt] = previews[key];
    previewImage.src = 'https://raw.githubusercontent.com/anshdeepofficial1/VYBE/main/docs/screenshots/vybe-' + key + '.png';
    previewImage.alt = alt;
    previewTitle.textContent = title;
    previewDescription.textContent = description;
    previewTabs.forEach(item => item.setAttribute('aria-pressed', String(item === tab)));
  }));

  const heroStage = document.querySelector('.hero-stage');
  const phoneButtons = Array.from(document.querySelectorAll('.phone-button'));
  const screenEyebrow = document.getElementById('screenEyebrow');
  const screenTitle = document.getElementById('screenTitle');
  const screenDescription = document.getElementById('screenDescription');

  function setActiveScreen(id) {
    if (!heroStage || !screenData[id]) return;
    heroStage.dataset.active = id;
    phoneButtons.forEach((button) => {
      const active = button.dataset.screenId === id;
      button.setAttribute('aria-pressed', String(active));
    });
    if (screenEyebrow) screenEyebrow.textContent = screenData[id].eyebrow;
    if (screenTitle) screenTitle.textContent = screenData[id].title;
    if (screenDescription) screenDescription.textContent = screenData[id].description;
  }

  if (heroStage && phoneButtons.length) {
    setActiveScreen('search');
    phoneButtons.forEach((button) => {
      button.addEventListener('mouseenter', () => {
        if (window.matchMedia('(hover: hover)').matches) setActiveScreen(button.dataset.screenId);
      });
      button.addEventListener('focus', () => setActiveScreen(button.dataset.screenId));
      button.addEventListener('click', () => setActiveScreen(button.dataset.screenId));
    });
  }

  backToTop?.addEventListener('click', () => window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' }));
  const toggleFab = () => {
    if (!backToTop) return;
    backToTop.classList.toggle('visible', window.scrollY > 520 && window.innerWidth > 860);
  };
  toggleFab();
  window.addEventListener('scroll', toggleFab, { passive: true });
  window.addEventListener('resize', toggleFab);

  const faqSearch = document.getElementById('faqSearch');
  faqSearch?.addEventListener('input', () => {
    const term = faqSearch.value.trim().toLowerCase();
    document.querySelectorAll('.faq-item').forEach((item) => {
      const text = item.textContent.toLowerCase();
      item.hidden = !!term && !text.includes(term);
    });
  });

  function humanSize(bytes) {
    if (!Number.isFinite(bytes)) return 'Android APK';
    const mib = bytes / 1024 / 1024;
    return `${mib.toFixed(1)} MB`;
  }

  function formatDate(value) {
    if (!value) return 'Latest stable release';
    const d = new Date(value);
    return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' }).format(d);
  }

  function showToast(message) {
    if (!toast || !message) return;
    toast.textContent = message;
    toast.classList.add('show');
    clearTimeout(showToast._timer);
    showToast._timer = setTimeout(() => toast.classList.remove('show'), 2200);
  }

  function simpleMarkdownToHtml(markdown) {
    if (!markdown) return '<p>No release notes were provided for this release.</p>';
    const lines = markdown.replace(/\r/g, '').split('\n');
    const blocks = [];
    let list = [];
    const flushList = () => {
      if (!list.length) return;
      blocks.push(`<ul>${list.map(item => `<li>${item}</li>`).join('')}</ul>`);
      list = [];
    };
    for (const rawLine of lines) {
      const line = rawLine.trim().replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      if (!line) {
        flushList();
        continue;
      }
      if (/^[-*+]\s+/.test(line)) {
        list.push(line.replace(/^[-*+]\s+/, ''));
        continue;
      }
      flushList();
      if (/^#{1,6}\s+/.test(line)) {
        const level = Math.min(4, (line.match(/^#+/) || ['#'])[0].length + 1);
        blocks.push(`<h${level}>${line.replace(/^#{1,6}\s+/, '')}</h${level}>`);
      } else {
        blocks.push(`<p>${line}</p>`);
      }
    }
    flushList();
    return blocks.join('');
  }

  async function hydrateRelease() {
    const versionTargets = document.querySelectorAll('[data-version]');
    versionTargets.forEach((el) => { if (!el.textContent.trim()) el.textContent = defaultVersion; });

    try {
      const controller = new AbortController();
      const abortTimer = setTimeout(() => controller.abort(), 5000);
      const response = await fetch(releaseApi, {
        headers: { Accept: 'application/vnd.github+json' },
        signal: controller.signal
      });
      clearTimeout(abortTimer);
      if (!response.ok) throw new Error(`GitHub API ${response.status}`);
      const release = await response.json();

      const version = release.tag_name || defaultVersion;
      const assets = Array.isArray(release.assets) ? release.assets : [];
      const apkAsset = assets.find((asset) => /universal/i.test(asset.name) && /\.apk$/i.test(asset.name)) || assets.find((asset) => /\.apk$/i.test(asset.name) && !/arm64|armv7|armeabi|x86/i.test(asset.name)) || assets.find((asset) => /arm64/i.test(asset.name) && /\.apk$/i.test(asset.name)) || assets.find((asset) => /\.apk$/i.test(asset.name));
      const downloadUrl = apkAsset?.browser_download_url || release.html_url || releaseFallback;

      document.querySelectorAll('[data-download]').forEach((link) => {
        link.href = downloadUrl;
      });
      document.querySelectorAll('[data-version]').forEach((el) => {
        el.textContent = version;
      });
      const assetName = document.getElementById('assetName');
      if (assetName) assetName.textContent = apkAsset?.name || 'Android APK';
      const apkSize = document.getElementById('apkSize');
      if (apkSize) apkSize.textContent = humanSize(apkAsset?.size);
      const releaseDate = document.getElementById('releaseDate');
      if (releaseDate) releaseDate.textContent = formatDate(release.published_at);
      const notes = document.getElementById('releaseNotes');
      if (notes) notes.innerHTML = simpleMarkdownToHtml(release.body || '');
    } catch (error) {
      document.querySelectorAll('[data-version]').forEach((el) => {
        if (!el.textContent.trim() || /loading/i.test(el.textContent)) el.textContent = defaultVersion;
      });
      document.querySelectorAll('[data-download]').forEach((link) => { link.href = releaseFallback; });
      const assetName = document.getElementById('assetName');
      if (assetName && /checking/i.test(assetName.textContent)) assetName.textContent = 'Android APK';
      const apkSize = document.getElementById('apkSize');
      if (apkSize && /checking/i.test(apkSize.textContent)) apkSize.textContent = 'GitHub release';
      const releaseDate = document.getElementById('releaseDate');
      if (releaseDate && /loading|checking/i.test(releaseDate.textContent)) releaseDate.textContent = 'Latest stable release';
      const notes = document.getElementById('releaseNotes');
      if (notes && /loading/i.test(notes.textContent)) {
        notes.innerHTML = '<p>Release notes could not be loaded right now. Use the official GitHub release page for the latest details.</p>';
      }
      console.info('Using VYBE release fallback:', error.message);
    }
  }

  if ('requestIdleCallback' in window) {
    requestIdleCallback(() => hydrateRelease(), { timeout: 1800 });
  } else {
    setTimeout(hydrateRelease, 700);
  }

  document.querySelectorAll('a[href^="#"]').forEach((anchor) => {
    anchor.addEventListener('click', (event) => {
      const hash = anchor.getAttribute('href');
      if (!hash || hash === '#') return;
      const target = document.querySelector(hash);
      if (!target) return;
      event.preventDefault();
      target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
      if (mobileNav?.classList.contains('is-open')) {
        mobileNav.classList.remove('is-open');
        menuButton?.setAttribute('aria-expanded', 'false');
      }
    });
  });

  document.querySelectorAll('[data-download]').forEach((link) => {
    link.addEventListener('click', () => {
      if (window.innerWidth <= 860) showToast('Opening latest VYBE release…');
    });
  });
})();
