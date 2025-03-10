import React from "react";
import { Route, Routes } from "react-router-dom";

import { ResourceNotFoundErrorBoundary } from "views/common/ResorceNotFoundErrorBoundary";
import { StartOverErrorView } from "views/common/StartOverErrorView";

import CreationFormPage from "../ConnectionPage/pages/CreationFormPage";
import { RoutePaths } from "../routePaths";
import AllDestinationsPage from "./pages/AllDestinationsPage";
import DestinationItemPage from "./pages/DestinationItemPage";
import CreateDestinationPage from "./pages/CreateDestinationPage";

const DestinationsPage: React.FC = () => {
  return (
    <Routes>
      <Route path={RoutePaths.DestinationNew} element={<CreateDestinationPage />} />
      <Route path={RoutePaths.ConnectionNew} element={<CreationFormPage />} />
      <Route
        path=":id"
        element={
          <ResourceNotFoundErrorBoundary errorComponent={<StartOverErrorView />}>
            <DestinationItemPage />
          </ResourceNotFoundErrorBoundary>
        }
      />
      <Route index element={<AllDestinationsPage />} />
    </Routes>
  );
};

export default DestinationsPage;
