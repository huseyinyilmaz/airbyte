// @ts-check
// Note: type annotations allow type checking and IDEs autocompletion

const lightCodeTheme = require('prism-react-renderer/themes/github');
const darkCodeTheme = require('prism-react-renderer/themes/dracula');

/** @type {import('@docusaurus/types').Config} */
const config = {
    title: 'Airbyte Documentation',
    tagline:
        'Airbyte is an open-source data integration platform to build ELT pipelines. Consolidate your data in your data warehouses, lakes and databases.',
    // this is non-functional, just a link back to OSS docs right now
    url: 'https://docs.airbyte.com/',
    baseUrl: '/',
    onBrokenLinks: 'warn',
    onBrokenMarkdownLinks: 'warn',
    favicon: 'img/favicon.png',
    organizationName: 'airbytehq', // Usually your GitHub org/user name.
    projectName: 'airbyte', // Usually your repo name.

    plugins:    [
                    [
                        require.resolve('@cmfcmf/docusaurus-search-local'), {indexBlog: false}
                    ]
                ],

    presets: [
        [
            'classic',
            /** @type {import('@docusaurus/preset-classic').Options} */
            ({
                docs: {
                    routeBasePath: '/',
                    sidebarPath: require.resolve('./sidebars.js'),
                    editUrl: 'https://github.com/airbytehq/airbyte/blob/gitbook/v1/README.md',
                    path: '../docs'
                },
                blog: false,
                theme: {
                    customCss: require.resolve('./src/css/custom.css'),
                },
            }),
        ],
    ],

    themeConfig:
        /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
        ({
            colorMode: {
                disableSwitch: false,
            },
            navbar: {
                title: '',
                logo: {
                    alt: 'Simple, secure and extensible data integration',
                    src: 'img/logo-dark.png',
                    srcDark: 'img/logo-light.png',
                    width: 140,
                    height: 40,
                },
                items: [
                    {
                        href: 'https://airbyte.io/',
                        position: 'left',
                        label: 'Home',
                    },
                    {
                        href: 'https://status.airbyte.io/',
                        label: 'Status',
                        position: 'left',
                    },
                    {
                        href: 'https://airbyte.io/recipes',
                        label: 'Recipes',
                        position: 'left',
                    },
                    {
                        href: 'https://discuss.airbyte.io/',
                        label: 'Discourse',
                        position: 'left',
                    },
                ],
            },
            prism: {
                theme: lightCodeTheme,
                darkTheme: darkCodeTheme,
            },
        }),
};

module.exports = config;
