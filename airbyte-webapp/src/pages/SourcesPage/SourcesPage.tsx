import React from "react";
import { Navigate, Route, Routes } from "react-router-dom";

import CreationFormPage from "pages/ConnectionPage/pages/CreationFormPage";
import { StartOverErrorView } from "views/common/StartOverErrorView";
import { ResourceNotFoundErrorBoundary } from "views/common/ResorceNotFoundErrorBoundary";

import { RoutePaths } from "../routePaths";
import SourceItemPage from "./pages/SourceItemPage";
import CreateSourcePage from "./pages/CreateSourcePage";
import AllSourcesPage from "./pages/AllSourcesPage";

const SourcesPage: React.FC = () => (
  <Routes>
    <Route path={RoutePaths.SourceNew} element={<CreateSourcePage />} />
    <Route path={RoutePaths.ConnectionNew} element={<CreationFormPage />} />
    <Route
      path=":id"
      element={
        <ResourceNotFoundErrorBoundary errorComponent={<StartOverErrorView />}>
          <SourceItemPage />
        </ResourceNotFoundErrorBoundary>
      }
    />
    <Route index element={<AllSourcesPage />} />
    <Route element={<Navigate to="" />} />
  </Routes>
);

export default SourcesPage;
