
import React, { useState, useMemo } from 'react';
import type { DataItem, Column, TableState } from './types';
import { MOCK_DATA, INITIAL_COLUMNS } from './constants';
import { SearchIcon, PlusIcon, EditIcon, TrashIcon, FilterIcon, ChevronDownIcon, ArrowUpDownIcon } from './components/Icons';
import DataModal from './components/DataModal';
import { useFetch } from '~/utils/requests';
import type { EntityRouteSchemaView } from '~/dev_generated/app_schemas/EntityRouteSchemaView';
interface CrudTableProps {
  entityName: string
}

export const CrudTable: React.FC<CrudTableProps> = ({ entityName }) => {
  const [data, setData] = useState<DataItem[]>(MOCK_DATA);
  // const [columns, setColumns] = useState<Column[]>(INITIAL_COLUMNS);
  const [state, setState] = useState<TableState>({
    search: '',
    sortBy: 'name',
    sortOrder: 'asc',
    filterRole: 'All',
    filterStatus: 'All',
    visibleColumns: INITIAL_COLUMNS.filter(c => c.visible).map(c => c.key),
  });

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<DataItem | null>(null);
  const [showColMenu, setShowColMenu] = useState(false);

  // Filtering and Sorting Logic
  const filteredData = useMemo(() => {
    let result = [...data];

    if (state.search) {
      const query = state.search.toLowerCase();
      result = result.filter(item =>
        item.name.toLowerCase().includes(query) ||
        item.email.toLowerCase().includes(query) ||
        item.department.toLowerCase().includes(query)
      );
    }

    if (state.filterRole !== 'All') {
      result = result.filter(item => item.role === state.filterRole);
    }

    if (state.filterStatus !== 'All') {
      result = result.filter(item => item.status === state.filterStatus);
    }

    result.sort((a, b) => {
      const aVal = a[state.sortBy];
      const bVal = b[state.sortBy];

      if (typeof aVal === 'string' && typeof bVal === 'string') {
        return state.sortOrder === 'asc'
          ? aVal.localeCompare(bVal)
          : bVal.localeCompare(aVal);
      }

      if (typeof aVal === 'number' && typeof bVal === 'number') {
        return state.sortOrder === 'asc' ? aVal - bVal : bVal - aVal;
      }

      return 0;
    });

    return result;
  }, [data, state]);


  const fetchSchema = useFetch({
    mode: "jsonData",
    appPath: `/api/v1/crud/schema/${entityName}`
  })


  const schema = useMemo(() => {
    return fetchSchema.jsonData as EntityRouteSchemaView
  }, [fetchSchema.jsonData])

  if (fetchSchema.error) {
    return (
      <div>Error : {`${fetchSchema.error}`}</div>
    )
  }

  if (fetchSchema.loading) {
    return (
      <div>Loading...</div>
    )
  }

  // CRUD Handlers
  const handleAdd = () => {
    setEditingItem(null);
    setIsModalOpen(true);
  };

  const handleEdit = (item: DataItem) => {
    setEditingItem(item);
    setIsModalOpen(true);
  };

  const handleDelete = (id: string) => {
    if (window.confirm('Are you sure you want to delete this record?')) {
      setData(prev => prev.filter(item => item.id !== id));
    }
  };

  const handleSave = (formData: Partial<DataItem>) => {
    if (editingItem) {
      setData(prev => prev.map(item => item.id === editingItem.id ? { ...item, ...formData } : item));
    } else {
      const newItem: DataItem = {
        ...(formData as DataItem),
        id: Math.random().toString(36).substr(2, 9),
        createdAt: new Date().toISOString().split('T')[0],
        lastLogin: 'Never',
      };
      setData(prev => [newItem, ...prev]);
    }
    setIsModalOpen(false);
  };

  const toggleSort = (key: keyof DataItem) => {
    setState(prev => ({
      ...prev,
      sortBy: key,
      sortOrder: prev.sortBy === key && prev.sortOrder === 'asc' ? 'desc' : 'asc'
    }));
  };

  const toggleColumn = (key: keyof DataItem | 'actions') => {
    // setColumns(prev => prev.map(c => c.key === key ? { ...c, visible: !c.visible } : c));
  };

  return (
    <div className="min-h-screen pb-12 flex flex-col">
      {/* Top Navigation Bar */}
      <div className="bg-white border-b border-gray-200 px-4 py-3 sm:px-8">
        <div className="max-w-7xl mx-auto flex flex-col justify-between gap-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3 grow">
              {/* <div className="bg-blue-600 p-2 rounded-lg shadow-blue-200 shadow-lg">
                <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 17v-2m3 2v-4m3 4v-6m2 10H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
              </div> */}
              <div className='text-center w-full'>
                <h1 className="text-lg font-extrabold tracking-tight text-gray-900 leading-tight">
                  {schema.tableDefine.ktClassSimpleName}
                </h1>
                {/* <p className="text-[10px] text-gray-500 font-medium uppercase tracking-tighter">Resource Management</p> */}
              </div>
            </div>
            <button
              onClick={handleAdd}
              className="sm:hidden flex items-center gap-1.5 px-4 py-2 bg-blue-600 text-white rounded-xl text-xs font-bold hover:bg-blue-700 transition-all shadow-md active:scale-95"
            >
              <PlusIcon className="w-4 h-4" />
              New
            </button>
          </div>

          <div className="flex flex-row-reverse flex-wrap items-center gap-2">
            <button
              onClick={handleAdd}
              className="hidden sm:flex items-center gap-2 px-5 py-2 bg-blue-600 text-white rounded-xl text-sm font-bold hover:bg-blue-700 transition-all shadow-md active:scale-95"
            >
              <PlusIcon />
              Add Record
            </button>

            {/* <select
              className="px-2 py-2 bg-white border border-gray-200 rounded-xl text-xs font-semibold text-gray-700 focus:ring-2 focus:ring-blue-500 outline-none cursor-pointer"
              value={state.filterRole}
              onChange={(e) => setState({ ...state, filterRole: e.target.value })}
            >
              <option value="All">All Roles</option>
              <option value="Admin">Admin</option>
              <option value="Editor">Editor</option>
              <option value="Viewer">Viewer</option>
              <option value="Manager">Manager</option>
            </select> */}


            <div className="relative">
              <button
                onClick={() => setShowColMenu(!showColMenu)}
                className="flex items-center gap-2 px-3 py-2 bg-white border border-gray-200 rounded-xl text-xs font-semibold text-gray-700 hover:bg-gray-50 transition-colors shadow-sm"
              >
                <FilterIcon className="w-4 h-4 text-gray-400" />
                Cols
                <ChevronDownIcon className={`w-3 h-3 transition-transform ${showColMenu ? 'rotate-180' : ''}`} />
              </button>
              {showColMenu && (
                <div className="absolute right-0 mt-2 w-48 bg-white border border-gray-100 rounded-xl shadow-xl z-50 p-2 space-y-1 animate-in fade-in slide-in-from-top-2">
                  <div className="max-h-48 overflow-y-auto">
                    {schema.tableDefine.columnList.map(col => (
                      <label key={col.ktPropertyName} className="flex items-center gap-2 px-2 py-1.5 hover:bg-gray-50 rounded-lg cursor-pointer transition-colors">
                        <input
                          type="checkbox"
                          // checked={col.visible}
                          // onChange={() => toggleColumn(col.key)}
                          className="w-3.5 h-3.5 rounded text-blue-600 focus:ring-blue-500 border-gray-300"
                        />
                        <span className="text-xs font-medium text-gray-700">{col.ktPropertyName}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* <div className="relative group flex-1 min-w-[200px]">
              <SearchIcon className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-blue-500 transition-colors" />
              <input
                type="text"
                placeholder="Search..."
                className="w-full pl-9 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-xl text-sm focus:ring-2 focus:ring-blue-500 focus:bg-white outline-none transition-all"
                value={state.search}
                onChange={(e) => setState({ ...state, search: e.target.value })}
              />
            </div> */}



          </div>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 w-full max-w-7xl mx-auto px-0 sm:px-4 lg:px-8 mt-4">
        <div className="bg-white sm:rounded-2xl shadow-sm sm:border border-gray-200 overflow-scroll">
          <div className="w-full">
            {/* Semantic HTML Table - Transformed with CSS for Mobile */}
            <table>
              <thead className="hidden sm:table-header-group bg-gray-50 border-b border-gray-100">
                <tr>
                  {schema.tableDefine.columnList.filter(c => true).map(col => (
                    <th
                      key={col.ktPropertyName}
                      // onClick={() => col.sortable && toggleSort(col.key as keyof DataItem)}
                      className={`px-4 py-4 text-[12px] font-bold text-gray-400 tracking-widest ${/*col.sortable*/false ? 'cursor-pointer hover:text-blue-600 transition-colors' : ''}`}
                    >
                      <span className="flex items-center gap-1.5">
                        {col.ktPropertyName}
                        {/* {col.sortable && (
                          <ArrowUpDownIcon className={`w-3 h-3 ${state.sortBy === col.key ? 'text-blue-600' : 'text-gray-200'}`} />
                        )} */}
                      </span>
                    </th>
                  ))}
                  <th>
                    {/* <title></title> */}
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 flex flex-col sm:table-row-group" style={{ maxHeight: "60vh" }}>
                {filteredData.length > 0 ? (
                  filteredData.map((item) => (
                    <tr
                      key={item.id}
                      className="flex flex-col sm:table-row hover:bg-blue-50/20 transition-colors group p-4 sm:p-0 border-b sm:border-b-0 relative"
                      style={{ minHeight: '120px' }} // Ensures we can fit ~5 items on a standard mobile height (~640-800px)
                    >
                      {schema.tableDefine.columnList.filter(c => true).map(col => {
                        const cellValue = item[col.ktPropertyName as keyof DataItem];

                        // Layout logic for mobile: name and actions are prioritized
                        // if (col.key === 'name') {
                        //   return (
                        //     <td key={col.key} className="sm:table-cell px-0 sm:px-4 py-1 sm:py-3 order-1 sm:order-none">
                        //       <div className="flex items-center gap-3">
                        //         <div className="w-8 h-8 sm:w-10 sm:h-10 rounded-lg bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white font-bold text-xs sm:text-sm">
                        //           {item.name.charAt(0)}
                        //         </div>
                        //         <div className="flex-1 min-w-0">
                        //           <div className="text-sm font-bold text-gray-900 truncate group-hover:text-blue-700 transition-colors">{item.name}</div>
                        //           <div className="text-[10px] text-gray-400 font-medium sm:hidden truncate">{item.email}</div>
                        //         </div>
                        //       </div>
                        //     </td>
                        //   );
                        // }

                        // if (col.key === 'actions') {
                        //   return (
                        //     <td key={col.key} className="sm:table-cell px-0 sm:px-4 py-2 sm:py-3 absolute right-4 top-4 sm:relative sm:right-auto sm:top-auto order-2 sm:order-none">
                        //       <div className="flex items-center gap-1 sm:gap-2">
                        //         <button
                        //           onClick={() => handleEdit(item)}
                        //           className="p-1.5 sm:p-2 text-blue-600 hover:bg-blue-100 rounded-lg transition-all"
                        //           title="Edit"
                        //         >
                        //           <EditIcon className="w-4 h-4 sm:w-[18px] sm:h-[18px]" />
                        //         </button>
                        //         <button
                        //           onClick={() => handleDelete(item.id)}
                        //           className="p-1.5 sm:p-2 text-red-600 hover:bg-red-100 rounded-lg transition-all"
                        //           title="Delete"
                        //         >
                        //           <TrashIcon className="w-4 h-4 sm:w-[18px] sm:h-[18px]" />
                        //         </button>
                        //       </div>
                        //     </td>
                        //   );
                        // }

                        // For other columns, display in a compact grid/wrap on mobile
                        const isPrimaryMobile = false//['status', 'role', 'progress'].includes(col.key);

                        let cellContent;
                        // if (col.key === 'status') {
                        //   const statusColors: Record<string, string> = {
                        //     Active: 'bg-emerald-100 text-emerald-700',
                        //     Inactive: 'bg-gray-100 text-gray-700',
                        //     Pending: 'bg-amber-100 text-amber-700',
                        //     Suspended: 'bg-rose-100 text-rose-700',
                        //   };
                        //   cellContent = (
                        //     <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold uppercase tracking-wide ${statusColors[item.status]}`}>
                        //       {item.status}
                        //     </span>
                        //   );
                        // } else if (col.key === 'role') {
                        //   const roleIcons: Record<string, string> = {
                        //     Admin: 'bg-purple-100 text-purple-700',
                        //     Editor: 'bg-indigo-100 text-indigo-700',
                        //     Viewer: 'bg-slate-100 text-slate-700',
                        //     Manager: 'bg-blue-100 text-blue-700',
                        //   };
                        //   cellContent = (
                        //     <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${roleIcons[item.role]}`}>
                        //       {item.role}
                        //     </span>
                        //   );
                        // } else if (col.key === 'progress') {
                        //   cellContent = (
                        //     <div className="flex items-center gap-2">
                        //       <div className="w-16 bg-gray-100 rounded-full h-1 overflow-hidden">
                        //         <div
                        //           className={`h-full ${item.progress > 80 ? 'bg-emerald-500' : item.progress > 40 ? 'bg-blue-500' : 'bg-amber-500'}`}
                        //           style={{ width: `${item.progress}%` }}
                        //         />
                        //       </div>
                        //       <span className="text-[9px] text-gray-500 font-bold">{item.progress}%</span>
                        //     </div>
                        //   );
                        // } else {
                        cellContent = <span className="text-gray-600 truncate">{String(cellValue)}</span>;
                        // }

                        return (
                          <td
                            key={col.ktPropertyName}
                            className={`sm:table-cell px-0 sm:px-4 py-0.5 sm:py-3 text-[11px] sm:text-sm font-medium order-3 sm:order-none flex sm:inline-block items-center justify-between sm:justify-start ${isPrimaryMobile ? 'w-auto' : 'hidden sm:table-cell'}`}
                          >
                            <span className="sm:hidden text-[9px] font-bold text-gray-400 uppercase mr-2">{col.ktPropertyName}:</span>
                            {cellContent}
                          </td>
                        );
                      })}

                      {/* Mobile-only: Secondary information wrapped beneath primary */}
                      <td className="sm:hidden order-4 flex flex-wrap gap-x-4 gap-y-1 mt-1 border-t border-gray-50 pt-2 pb-1">
                        {schema.tableDefine.columnList.filter(c => true
                          // c.visible && !['name', 'actions', 'status', 'role', 'progress'].includes(c.key)
                        ).map(col => (
                          <div key={col.ktPropertyName} className="flex items-center gap-1.5">
                            <span className="text-[9px] font-bold text-gray-300 uppercase">{col.ktPropertyName}:</span>
                            <span className="text-[10px] text-gray-500 font-medium truncate max-w-[100px]">{String(item[col.ktPropertyName as keyof DataItem])}</span>
                          </div>
                        ))}
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr className="flex flex-col sm:table-row">
                    <td colSpan={schema.tableDefine.columnList.filter(c => true).length} className="px-4 py-20 text-center flex flex-col items-center">
                      <div className="bg-gray-100 p-3 rounded-full mb-3">
                        <SearchIcon className="w-6 h-6 text-gray-300" />
                      </div>
                      <p className="text-gray-400 text-sm font-medium">No results found.</p>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="mt-auto px-4 py-4 max-w-7xl mx-auto w-full flex items-center justify-between text-[10px] sm:text-xs text-gray-400 font-medium uppercase tracking-widest border-t border-gray-100 sm:border-0">
        <div>Total: {filteredData.length} Records</div>
      </div>

      <DataModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSave={handleSave}
        initialData={editingItem}
      />
    </div>
  );
};

