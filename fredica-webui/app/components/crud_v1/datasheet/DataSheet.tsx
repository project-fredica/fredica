
import React, { useMemo, useState } from 'react';
import { Header } from './components/Header';
import { Toolbar } from './components/Toolbar';
import { DataTable } from './components/DataTable';
import { Footer } from './components/Footer';
import { MOCK_CONTACTS } from './constants';
import type { EntityRouteSchemaView } from '~/dev_generated/app_schemas/EntityRouteSchemaView';
import { useFetch } from '~/utils/requests';
import type { DataRow } from './types';
interface DataSheetProps {
  entityName: string
}
export const DataSheet: React.FC<DataSheetProps> = ({ entityName }) => {
  const [data, setData] = useState<DataRow[]>(MOCK_CONTACTS);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const [selectedRowId, setSelectedRowId] = useState<number | null>(null);
  const [visibleColumns, setVisibleColumns] = useState<Record<string, boolean>>({
    // contactName: true,
    // contactId: true,
    // title: true,
    // profilePicture: true,
    // email: true,
    // subscription: true,
    // registrationNumber: true,
    // paidMember: true,
    // postalAddress: true,
  });

  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({
    // contactName: 180,
    // contactId: 100,
    // title: 150,
    // profilePicture: 100,
    // email: 200,
    // subscription: 120,
    // registrationNumber: 150,
    // paidMember: 80,
    // postalAddress: 250,
  });
  const fetchSchema = useFetch({
    mode: "jsonData",
    appPath: `/api/v1/crud/schema/${entityName}`
  })

  const schema = useMemo(() => {
    return fetchSchema.jsonData as EntityRouteSchemaView
  }, [fetchSchema.jsonData])

  // Derived data for pagination
  const paginatedContacts = useMemo(() => {
    const startIndex = (currentPage - 1) * pageSize;
    return data.slice(startIndex, startIndex + pageSize);
  }, [data, currentPage, pageSize]);

  const totalRecords = data.length;
  const totalPages = Math.ceil(totalRecords / pageSize);

  const handleCellEdit = (id: number, field: string, value: any) => {
    setData(prev => prev.map(c => c.id === id ? { ...c, [field]: value } : c));
  };

  const handleDeleteRow = () => {
    if (selectedRowId !== null) {
      setData(prev => prev.filter(c => c.id !== selectedRowId));
      setSelectedRowId(null);
    }
  };

  const toggleColumn = (columnKey: string) => {
    setVisibleColumns(prev => ({ ...prev, [columnKey]: !prev[columnKey] }));
  };

  const handleResizeColumn = (key: string, width: number) => {
    setColumnWidths(prev => ({ ...prev, [key]: width }));
  };


  if (fetchSchema.error) {
    return (
      <div className='flex justify-center p-4'>
        <p>
          Error!
        </p>
        <p>{`${fetchSchema.error}`}</p>
      </div>
    )
  }

  if (fetchSchema.loading) {
    return (
      <div className='flex justify-center p-4'>
        <p>
          Loding...
        </p>
      </div>
    )
  }

  return (
    <div className="flex h-screen overflow-hidden bg-white">
      {/* Fixed Sidebar */}
      {/* <Sidebar /> */}


      {/* Main Workspace Area */}
      <main className="flex-1 flex flex-col min-w-0 h-full">
        <Toolbar
          onDeleteRow={handleDeleteRow}
          hasSelection={selectedRowId !== null}
          visibleColumns={visibleColumns}
          onToggleColumn={toggleColumn}
        />

        <DataTable
          schema={schema}
          data={paginatedContacts}
          onEdit={handleCellEdit}
          selectedRowId={selectedRowId}
          onSelectRow={setSelectedRowId}
          visibleColumns={visibleColumns}
          columnWidths={columnWidths}
          onResizeColumn={handleResizeColumn}
          offset={(currentPage - 1) * pageSize}
        />

        <Footer
          currentPage={currentPage}
          totalPages={totalPages}
          pageSize={pageSize}
          totalRecords={totalRecords}
          onPageChange={setCurrentPage}
          onPageSizeChange={setPageSize}
        />
      </main>

    </div>
  );
};