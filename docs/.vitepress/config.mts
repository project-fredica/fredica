import { DefaultTheme, defineConfig } from "vitepress";
import { generateSidebar } from "vitepress-sidebar";
import { withMermaid } from "vitepress-plugin-mermaid";
import taskCheckbox from "markdown-it-task-checkbox"; // ts ignore

function create_sidebar_item(): DefaultTheme.SidebarItem[] {
  /* @ts-ignore */
  return generateSidebar({
    documentRootPath: "docs",
    useTitleFromFileHeading: true,
    useFolderTitleFromIndexFile: true,
    useFolderLinkFromIndexFile: true,
    collapseDepth: 2,
    collapsed: true,
    sortMenusOrderNumericallyFromTitle: true,
    underscoreToSpace: true,
  });
}

const createVitePressConfig = () =>
  defineConfig({
    title: "Fredica",
    description: "AI 驱动的视频创作辅助工具",
    lang: "zh-CN",
    base: "/fredica/",
    ignoreDeadLinks: "localhostLinks",
    themeConfig: {
      nav: [
        { text: "首页", link: "/" },
        { text: "用户文档", link: "/guide/", activeMatch: "/guide/" },
        { text: "产品文档", link: "/product/", activeMatch: "/product/" },
        { text: "开发文档", link: "/dev/", activeMatch: "/dev/" },
      ],

      socialLinks: [
        { icon: "github", link: "https://github.com/project-fredica/fredica" },
      ],
      editLink: {
        pattern: (pagedata) => {
          return `https://github.com/Xpectuer/LibianCrawlers/blob/main/docs/${
            encodeURIComponent(
              pagedata.filePath,
            )
          }`;
        },
        text: "去Github编辑",
      },
      sidebar: [...create_sidebar_item()],
      footer: {
        message: "Fredica — AI 视频工坊",
      },

      search: {
        provider: "local",
      },

      outline: {
        label: "本页目录",
        level: [2, 3],
      },

      docFooter: {
        prev: "上一页",
        next: "下一页",
      },

      lastUpdated: {
        text: "最后更新于",
      },
    },
    markdown: {
      // options for @mdit-vue/plugin-toc
      // https://github.com/mdit-vue/mdit-vue/tree/main/packages/plugin-toc#options
      toc: { level: [1, 2, 3, 4, 5, 6] },
      lineNumbers: true,
      config: (md) => {
        md.use(taskCheckbox, {
          disabled: true,
          divWrap: false,
          divClass: "checkbox",
          idPrefix: "cbx_",
          ulClass: "task-list",
          liClass: "task-list-item",
        });
      },
    },
    lastUpdated: true,
  });

export default withMermaid(
  createVitePressConfig(),
);
