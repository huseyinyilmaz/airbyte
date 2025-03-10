import React, { useState } from "react";
import { FormattedMessage } from "react-intl";

import PageTitle from "components/PageTitle";
import { FormPageContent } from "components/ConnectorBlocks";
import HeadTitle from "components/HeadTitle";

import useRouter from "hooks/useRouter";
import { ConnectionConfiguration } from "core/domain/connection";
import { useDestinationDefinitionList } from "services/connector/DestinationDefinitionService";
import { useCreateDestination } from "hooks/services/useDestinationHook";

import DestinationForm from "./components/DestinationForm";

const CreateDestinationPage: React.FC = () => {
  const { push } = useRouter();
  const [successRequest, setSuccessRequest] = useState(false);

  const { destinationDefinitions } = useDestinationDefinitionList();
  const { mutateAsync: createDestination } = useCreateDestination();

  const onSubmitDestinationForm = async (values: {
    name: string;
    serviceType: string;
    connectionConfiguration?: ConnectionConfiguration;
  }) => {
    const connector = destinationDefinitions.find((item) => item.destinationDefinitionId === values.serviceType);
    const result = await createDestination({
      values,
      destinationConnector: connector,
    });
    setSuccessRequest(true);
    setTimeout(() => {
      setSuccessRequest(false);
      push(`../${result.destinationId}`);
    }, 2000);
  };

  return (
    <>
      <HeadTitle titles={[{ id: "destinations.newDestinationTitle" }]} />
      <PageTitle withLine title={<FormattedMessage id="destinations.newDestinationTitle" />} />
      <FormPageContent>
        <DestinationForm
          onSubmit={onSubmitDestinationForm}
          destinationDefinitions={destinationDefinitions}
          hasSuccess={successRequest}
        />
      </FormPageContent>
    </>
  );
};

export default CreateDestinationPage;
