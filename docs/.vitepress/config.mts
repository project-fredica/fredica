import { defineConfig } from 'vitepress'
import { withSidebar } from 'vitepress-sidebar'
import { withMermaid } from 'vitepress-plugin-mermaid'

const vitePressConfig = defineConfig({
  title: 'Fredica',
  description: 'AI 驱动的视频创作辅助工具',
  lang: 'zh-CN',

  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '用户文档', link: '/guide/', activeMatch: '/guide/' },
      { text: '产品文档', link: '/product/', activeMatch: '/product/' },
      { text: '开发文档', link: '/dev/', activeMatch: '/dev/' },
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/project-fredica/fredica' }
    ],

    footer: {
      message: 'Fredica — AI 视频工坊',
    },

    search: {
      provider: 'local'
    },

    outline: {
      label: '本页目录',
      level: [2, 3],
    },

    docFooter: {
      prev: '上一页',
      next: '下一页',
    },

    lastUpdated: {
      text: '最后更新于',
    },
  },

  lastUpdated: true,
})

export default withMermaid(
  withSidebar(vitePressConfig, [
    // 用户文档
    {
      documentRootPath: 'docs',
      scanStartPath: 'guide',
      resolvePath: '/guide/',
      useTitleFromFrontmatter: true,
      useFolderTitleFromIndexFile: true,
      sortMenusByFrontmatterOrder: true,
      frontmatterOrderDefaultValue: 99,
      collapsed: false,
      excludePattern: ['index.md'],
    },
    // 产品文档
    {
      documentRootPath: 'docs',
      scanStartPath: 'product',
      resolvePath: '/product/',
      useTitleFromFrontmatter: true,
      useFolderTitleFromIndexFile: true,
      sortMenusByFrontmatterOrder: true,
      frontmatterOrderDefaultValue: 99,
      collapsed: false,
      excludePattern: ['index.md'],
    },
    // 开发文档
    {
      documentRootPath: 'docs',
      scanStartPath: 'dev',
      resolvePath: '/dev/',
      useTitleFromFrontmatter: true,
      useFolderTitleFromIndexFile: true,
      sortMenusByFrontmatterOrder: true,
      frontmatterOrderDefaultValue: 99,
      collapsed: false,
      excludePattern: ['index.md'],
    },
  ])
)
