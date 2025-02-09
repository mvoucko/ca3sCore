'use strict';
const utils = require('./vue.utils');
const webpack = require('webpack');
const config = require('../config');
const merge = require('webpack-merge');
const baseWebpackConfig = require('./webpack.common');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');
const OptimizeCSSPlugin = require('optimize-css-assets-webpack-plugin');
const UglifyJsPlugin = require('uglifyjs-webpack-plugin');
const jhiUtils = require('./utils.js');

const env = require('../config/prod.env');

const webpackConfig = merge(baseWebpackConfig, {
  mode: 'production',
  module: {
    rules: utils.styleLoaders({
      sourceMap: config.build.productionSourceMap,
      extract: true,
      usePostCSS: true
    })
  },
  devtool: config.build.productionSourceMap ? config.build.devtool : false,
  entry: {
    global: './src/main/webapp/content/scss/global.scss',
    main: './src/main/webapp/app/main'
  },
  output: {
    path: jhiUtils.root('target/classes/static/'),
    filename: 'app/[name].[hash].bundle.js',
    chunkFilename: 'app/[id].[hash].chunk.js'
  },
  plugins: [
    // http://vuejs.github.io/vue-loader/en/workflow/production.html
    new webpack.DefinePlugin({
      'process.env': env
    }),
    /*
    new UglifyJsPlugin({
      uglifyOptions: {
        compress: {
          warnings: false
        }
      },
      sourceMap: config.build.productionSourceMap,
      parallel: true
    }),
*/
    // extract css into its own file
    new MiniCssExtractPlugin({
      filename: '[name].[contenthash].css',
      chunkFilename: '[id].css'
    }),
    // Compress extracted CSS. We are using this plugin so that possible
    // duplicated CSS from different components can be deduped.
    new OptimizeCSSPlugin({}),
    // generate dist index.html with correct asset hash for caching.
    // you can customize output by editing /index.html
    // see https://github.com/ampedandwired/html-webpack-plugin
    new HtmlWebpackPlugin({
      template: './src/main/webapp/index.html',
      chunks: ['vendors', 'main', 'global'],
      //      chunksSortMode: 'manual',
      inject: true,

      //      minify: {
      //        removeComments: true,
      //        collapseWhitespace: true,
      //        removeAttributeQuotes: true
      // more options:
      // https://github.com/kangax/html-minifier#options-quick-reference
      //      },
      // necessary to consistently work with multiple chunks via CommonsChunkPlugin
      chunksSortMode: 'dependency'
    }),
    // keep module.id stable when vendor modules does not change
    new webpack.HashedModuleIdsPlugin()
  ]
});

if (config.build.productionGzip) {
  const CompressionWebpackPlugin = require('compression-webpack-plugin');

  webpackConfig.plugins.push(
    new CompressionWebpackPlugin({
      asset: '[path].gz[query]',
      algorithm: 'gzip',
      test: new RegExp('\\.(' + config.build.productionGzipExtensions.join('|') + ')$'),
      threshold: 10240,
      minRatio: 0.8
    })
  );
}

if (config.build.bundleAnalyzerReport) {
  const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;
  webpackConfig.plugins.push(new BundleAnalyzerPlugin());
}

module.exports = webpackConfig;
