export const SAMPLE_BODY = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://example.com/webservice">
  <soapenv:Header/>
  <soapenv:Body>
    <web:GetOrder>
      <web:OrderId>___ORDER_ID:1001___</web:OrderId>
    </web:GetOrder>
  </soapenv:Body>
</soapenv:Envelope>
`;
